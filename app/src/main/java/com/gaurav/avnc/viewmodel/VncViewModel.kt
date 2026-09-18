/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.viewmodel

import android.app.Application
import android.content.pm.ActivityInfo
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import com.termux.R
import com.gaurav.avnc.model.LoginInfo
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.session.RemoteSession
import com.gaurav.avnc.ui.vnc.FrameScroller
import com.gaurav.avnc.ui.vnc.FrameState
import com.gaurav.avnc.ui.vnc.FrameView
import com.gaurav.avnc.util.LiveEvent
import com.gaurav.avnc.util.LiveRequest
import com.gaurav.avnc.util.Tones
import com.gaurav.avnc.util.debugCheck
import com.gaurav.avnc.util.getClipboardText
import com.gaurav.avnc.util.getKnownHostsFile
import com.gaurav.avnc.util.getUnknownCertificateMessage
import com.gaurav.avnc.util.isCertificateTrusted
import com.gaurav.avnc.util.isTrue
import com.gaurav.avnc.util.monitor
import com.gaurav.avnc.util.setClipboardText
import com.gaurav.avnc.util.trustCertificate
import com.gaurav.avnc.viewmodel.service.SshClient
import com.gaurav.avnc.session.Messenger
import com.gaurav.avnc.vnc.UserCredential
import com.gaurav.avnc.vnc.VncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.lang.ref.WeakReference
import java.security.cert.X509Certificate

/**
 * ViewModel for VncActivity
 *
 * Connection
 * ==========
 *
 * At construction, we instantiate a [VncClient] referenced by [client]. Then
 * activity starts the connection by calling [initConnection] which starts a coroutine to
 * handle connection setup.
 *
 * After successful connection, we continue to operate normally until the remote
 * server closes the connection, or an error occurs. Once disconnected, we
 * wait for the activity to finish and then cleanup any acquired resources.
 *
 * Currently, lifecycle of [client] is tied to this view model. So one [VncViewModel]
 * manages only one [VncClient].
 *
 *
 * Threading
 * =========
 *
 * Receiver thread :- This thread is started in [launchConnection].
 * It handles the protocol initialization, and after that processes incoming messages.
 * Most of the callbacks of [VncClient.Observer] are invoked on this thread. In most
 * cases it is stopped when activity is finished and this view model is cleaned up.
 *
 * Sender thread :- This thread is created (as an executor) by [messenger]. It is
 * used to send messages to remote server. We use this dedicated thread instead
 * of coroutines to preserve the order of sent messages.
 *
 * UI thread :- Main thread of the app. Used for updating UI and controlling other
 * Threads. This is where [frameState] is updated.
 *
 * Renderer thread :- This is managed by [FrameView] and used for rendering frame
 * via OpenGL ES. [frameState] is read from this thread to decide how/where frame
 * should be drawn.
 */
class VncViewModel(app: Application) : BaseViewModel(app) {

    /**
     * Connection lifecycle:
     *
     *            Created
     *               |
     *               v
     *          Connecting ----------+
     *               |               |
     *               v               |
     *           Connected           |
     *               |               |
     *               v               |
     *          Disconnected <-------+
     *
     */
    enum class State {
        Created,
        Connecting,
        Connected,
        Disconnected;
    }

    lateinit var profile: ServerProfile

    /**
     * Live version of [profile], allows easier access from UI layer
     */
    val profileLive = MutableLiveData<ServerProfile>()

    var client: VncClient? = null

    private val session = RemoteSession(RemoteSessionObserver())

    /**
     * We have two places for connection state (both are synced):
     *
     * [VncClient.connected] - Simple boolean state, used most of the time
     * [state]               - More granular, used by observers & data binding
     */
    val state = MutableLiveData(State.Created)

    val connected get() = state.value == State.Connected

    /**
     * Reason for disconnecting.
     */
    val disconnectReason = MutableLiveData("")

    /**
     * Fired when we need some credentials from user.
     * It will trigger the Login dialog.
     */
    val loginInfoRequest = LiveRequest<LoginInfo.Type, LoginInfo>()

    /**
     * List of saved profiles.
     * Used by login-autocompletion.
     */
    val savedProfiles by lazy { serverProfileDao.getLiveList() }

    /**
     * Holds a weak reference to [FrameView] instance.
     *
     * This is used to tell [FrameView] to re-render its content when VncClient's
     * framebuffer is updated. Instead of using LiveData/LiveEvent, we keep a
     * weak reference because:
     *
     *      1. It avoids a context-switch to UI thread. Rendering request to
     *         a GlSurfaceView can be sent from any thread.
     *
     *      2. We don't have to invoke the whole ViewModel machinery just for
     *         a single call to FrameView.
     */
    var frameViewRef = WeakReference<FrameView>(null)

    /**
     * Holds information about scaling, translation etc.
     */
    val frameState = with(pref.viewer) { FrameState(zoomMin, zoomMax, perOrientationZoom) }

    /**
     * Used for scrolling/animating the frame.
     */
    val frameScroller = FrameScroller(this)

    /**
     * Currently active gesture style
     */
    val activeGestureStyle = MutableLiveData<String>()

    val activeViewMode = MutableLiveData<Int>()
    val videoDisabled get() = (activeViewMode.value == ServerProfile.VIEW_MODE_NO_VIDEO)

    /**
     * Used for sending events to remote server.
     */
    var messenger: Messenger? = null

    /**************************************************************************
     * QEMU VNC Audio Extension (Termux Ultra)
     **************************************************************************/

    /**
     * Whether audio button should be ON (for UI data binding).
     * Controlled by user action via toolbar audio button.
     */
    val audioStarted = MutableLiveData(false)

    /** AudioTrack instance used for playback of PCM data received via VNC */
    @Volatile
    private var audioTrack: AudioTrack? = null

    /** Audio sample format used for QEMU VNC extension (matches rfbproto.h rfbQemuAudioFmtS16) */
    private val audioQemuFormatCode = 3  // rfbQemuAudioFmtS16

    /** Preferred audio parameters for AudioTrack + QEMU VNC audio extension */
    private val audioSampleRate = 44100   // Hz
    private val audioChannelCount = 2     // Stereo
    private val audioAndroidChannels = AudioFormat.CHANNEL_OUT_STEREO
    private val audioAndroidEncoding = AudioFormat.ENCODING_PCM_16BIT

    /** Audio connection error request: fires a message to UI layer showing a toast */
    val audioErrorMessage = LiveEvent<String>()

    /** Release audio resources (AudioTrack) */
    private fun releaseAudio() {
        try {
            audioTrack?.apply {
                try { stop() } catch (_: Throwable) {}
                try { release() } catch (_: Throwable) {}
            }
        } finally {
            audioTrack = null
        }
    }

    /** Create and initialize AudioTrack with preferred parameters */
    private fun initAudioTrack(): AudioTrack? {
        return try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                audioSampleRate,
                audioAndroidChannels,
                audioAndroidEncoding
            )
            if (minBufferSize <= 0) {
                Log.e(javaClass.simpleName, "AudioTrack.getMinBufferSize returned $minBufferSize, audio disabled")
                return null
            }

            // Use 4x min buffer to reduce glitches during playback
            val bufferSize = minBufferSize * 4

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(audioSampleRate)
                .setChannelMask(audioAndroidChannels)
                .setEncoding(audioAndroidEncoding)
                .build()

            val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    audioSampleRate,
                    audioAndroidChannels,
                    audioAndroidEncoding,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
            }

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(javaClass.simpleName, "AudioTrack not initialized (state=${track.state})")
                try { track.release() } catch (_: Throwable) {}
                return null
            }

            track.play()
            track
        } catch (t: Throwable) {
            Log.e(javaClass.simpleName, "Failed to initialize AudioTrack", t)
            null
        }
    }

    /**
     * Start QEMU VNC audio streaming (SET_FORMAT + ENABLE) via messenger thread.
     * Called when user manually taps the toolbar audio button.
     * Returns true if command was successfully enqueued.
     */
    private fun startAudioStreaming(): Boolean {
        val m = messenger ?: return false
        if (!connected) return false
        if (audioStarted.value == true) return true

        launchIO {
            try {
                // First send SetFormat so server knows our PCM preferences
                val formatResult = m.sendQemuAudioSetFormat(audioChannelCount, audioQemuFormatCode, audioSampleRate)
                // Then enable streaming
                val enableResult = m.sendQemuAudioEnable()

                if (formatResult || enableResult) {
                    Log.d(javaClass.simpleName, "QEMU audio streaming started (format=$formatResult, enable=$enableResult)")
                    audioStarted.postValue(true)
                } else {
                    Log.w(javaClass.simpleName, "QEMU audio commands returned false - server may not support audio extension")
                    audioStarted.postValue(false)
                    audioErrorMessage.fireAsync(
                        app.getString(R.string.msg_audio_connection_failed)
                    )
                }
            } catch (t: Throwable) {
                Log.e(javaClass.simpleName, "Failed to start QEMU VNC audio", t)
                audioStarted.postValue(false)
                // Any error from audio commands is swallowed - never disconnect VNC because of audio
                audioErrorMessage.fireAsync(
                    app.getString(R.string.msg_audio_connection_failed)
                )
            }
        }
        return true
    }

    /** Stop QEMU VNC audio streaming (DISABLE + release resources) */
    private fun stopAudioStreaming() {
        try {
            messenger?.sendQemuAudioDisable()
        } catch (_: Throwable) {}
        audioStarted.postValue(false)
        releaseAudio()
    }

    /** JNI callback: audio stream has begun */
    private fun onQemuAudioBeginImpl() {
        if (audioTrack == null) {
            audioTrack = initAudioTrack()
        }
        // If already created, make sure it's playing
        try {
            audioTrack?.takeIf { it.playState != AudioTrack.PLAYSTATE_PLAYING }?.play()
        } catch (_: Throwable) {}
    }

    /** JNI callback: PCM audio data received */
    private fun onQemuAudioDataImpl(data: ByteArray) {
        if (data.isEmpty()) return
        val track = audioTrack ?: return
        try {
            track.write(data, 0, data.size)
        } catch (t: Throwable) {
            Log.e(javaClass.simpleName, "AudioTrack.write failed", t)
            releaseAudio()
        }
    }

    /** JNI callback: audio stream has ended */
    private fun onQemuAudioEndImpl() {
        releaseAudio()
    }

    /**
     * Toggle QEMU VNC audio on/off.
     * Exposed to UI layer (toolbar button).
     * Audio errors never interrupt the VNC connection - only a toast is shown.
     */
    fun toggleAudio() {
        if (!connected) return
        val shouldEnable = audioStarted.value != true
        if (shouldEnable) {
            startAudioStreaming()
        } else {
            stopAudioStreaming()
        }
    }

    /**
     * Used to confirm something with user before continuing.
     * This is mostly used to warn about unknown SSH host, x509 certificates etc.
     * This request accepts two strings: First is used as title, second contains the message.
     */
    val confirmationRequest = LiveRequest<Pair<String, String>, Boolean>()

    /**
     * If user has asked to remember credentials in login dialog, we need to
     * save them to database. But we don't want to save them immediately
     * because user might have mistyped them. So, we wait until successful
     * connection before saving them.
     */
    var loginInfoToBeRemembered = mutableListOf<LoginInfo>()

    /**
     * Whether activity window is currently focused
     */
    val hasWindowFocus = MutableLiveData<Boolean>()

    /**
     * Whether viewer help is being shown
     */
    val viewerHelpIsVisible = MutableLiveData<Boolean>()

    /**
     * Whether activity is in Picture-in-Picture mode
     */
    val inPiPMode = MutableLiveData<Boolean>()

    val preferredScreenOrientation = monitor(profileLive) { resolveScreenOrientation() }

    val capturePointer = monitor(state, hasWindowFocus, viewerHelpIsVisible) { resolveCapturePointer() }

    override fun onCleared() {
        super.onCleared()
        stopAudioStreaming()
        session.stop()
    }


    /**************************************************************************
     * Connection management
     **************************************************************************/

    /**
     * Initialize VNC connection.
     * It can be called multiple times due to activity restarts.
     */
    fun initConnection(profile: ServerProfile) {
        if (state.value == State.Created) {
            this.profile = profile
            profileLive.value = profile
            state.value = State.Connecting
            frameState.setZoom(profile.zoom1, profile.zoom2)
            setViewMode(profile.viewMode)
            resolveGestureStyle()
            inPiPMode.observeForever { if (it) resetZoom() }
            session.start(profile)
        }
    }

    /**
     * Can be used to persist any changes made to [profile]
     */
    fun saveProfile() {
        if (profile.ID != 0L)
            launchMain { serverProfileDao.update(profile) }
    }

    suspend fun getProfileById(id: Long) = serverProfileDao.getByID(id)

    /**************************************************************************
     * Frame management
     **************************************************************************/

    fun updateZoom(scaleFactor: Float, fx: Float, fy: Float) {
        if (profile.fZoomLocked || videoDisabled) return

        frameState.updateZoom(scaleFactor, fx, fy)
        refreshFrameView()
    }

    fun resetZoom() {
        frameState.setZoom(1f, 1f)
        refreshFrameView()
    }

    fun resetZoomToDefault() {
        frameState.setZoom(profile.zoom1, profile.zoom2)
        refreshFrameView()
    }

    fun setZoom(zoom1: Float, zoom2: Float) {
        frameState.setZoom(zoom1, zoom2)
        refreshFrameView()
    }

    fun panFrame(deltaX: Float, deltaY: Float) {
        frameState.pan(deltaX, deltaY)
        refreshFrameView()
    }

    fun moveFrameTo(x: Float, y: Float) {
        frameState.moveTo(x, y)
        refreshFrameView()
    }

    fun toggleZoomLock(enabled: Boolean) {
        profile.fZoomLocked = enabled
        saveProfile()
    }

    fun saveZoom() {
        profile.zoom1 = frameState.zoomScale1
        profile.zoom2 = frameState.zoomScale2
        saveProfile()
    }

    fun setSafeArea(safeArea: RectF) {
        frameState.setSafeArea(safeArea)
        refreshFrameView()
    }

    /**************************************************************************
     * Miscellaneous
     **************************************************************************/

    fun sendClipboardText() {
        if (pref.server.clipboardSync && connected) launchIO {
            getClipboardText(app)?.let { messenger?.sendClipboardText(it) }
        }
    }

    private var clipReceiverJob: Job? = null
    private fun receiveClipboardText(text: String) {
        if (!pref.server.clipboardSync)
            return

        // This is a protective measure against servers which send every 'selection' made on the server.
        // Setting clip text involves IPC, so these events can exhaust Binder resources, leading to ANRs.
        if (clipReceiverJob?.isActive == true) {
            Log.w(javaClass.simpleName, "Dropping clip text received from server, previous text is still pending")
            return
        }

        clipReceiverJob = launchIO {
            setClipboardText(app, text)
        }
    }

    fun getLoginInfo(type: LoginInfo.Type): LoginInfo {
        val li = LoginInfo.fromProfile(profile, type)

        // If info is already available, return it
        if ((type == LoginInfo.Type.VNC_PASSWORD && li.password.isNotBlank()) ||
            (type == LoginInfo.Type.VNC_CREDENTIAL && li.username.isNotBlank() && li.password.isNotBlank()) ||
            (type == LoginInfo.Type.SSH_PASSWORD && li.password.isNotBlank()))
            return li

        // Something is missing, so we have to ask the user
        return loginInfoRequest.getResponseFor(type)  // Blocking call
    }

    /**
     * Resize remote desktop to match with local window size (if requested by user).
     * In portrait mode, safe area is used instead of window to exclude the keyboard.
     */
    fun resizeRemoteDesktop() {
        if (connected && profile.resizeRemoteDesktop && !videoDisabled)
            frameState.let {
                if (it.windowWidth > it.windowHeight)
                    messenger?.setDesktopSize(it.windowWidth.toInt(), it.windowHeight.toInt())
                else
                    messenger?.setDesktopSize(it.safeArea.width().toInt(), it.safeArea.height().toInt())
            }
    }

    fun setFrameBufferUpdatesPaused(paused: Boolean) {
        messenger?.setFrameBufferUpdatesPaused(paused)
    }

    fun refreshFrameBuffer() {
        messenger?.refreshFrameBuffer()
    }

    /**
     * Sets gesture style of profile to given value.
     * Any change will be reflected in [activeGestureStyle].
     */
    fun setGestureStyle(newStyle: String) {
        if (profile.gestureStyle != newStyle) {
            profile.gestureStyle = newStyle
            saveProfile()
        }

        resolveGestureStyle()
    }

    private fun resolveGestureStyle() {
        var gestureStyle = profile.gestureStyle

        if (gestureStyle == "auto")
            gestureStyle = pref.input.gesture.style

        if (videoDisabled)
            gestureStyle = "touchpad"

        check(gestureStyle.isNotBlank())
        if (activeGestureStyle.value != gestureStyle)
            activeGestureStyle.value = gestureStyle
    }

    fun setViewMode(newMode: Int) {
        if (profile.viewMode != newMode) {
            profile.viewMode = newMode
            saveProfile()
        }

        if (activeViewMode.value != newMode) {
            activeViewMode.value = newMode
            client?.setInputDisabled(newMode == ServerProfile.VIEW_MODE_NO_INPUT)
            setFrameBufferUpdatesPaused(newMode == ServerProfile.VIEW_MODE_NO_VIDEO)
            resolveGestureStyle()
        }
    }

    private fun refreshFrameView() {
        frameViewRef.get()?.requestRender()
    }

    private fun rememberLoginInfo() {
        if (loginInfoToBeRemembered.isNotEmpty()) {
            debugCheck(profile.ID != 0L)
            loginInfoToBeRemembered.forEach { it.applyTo(profile) }
            loginInfoToBeRemembered.clear()
            saveProfile()
        }
    }

    private fun resolveScreenOrientation(): Int {
        var choice = profileLive.value?.screenOrientation
        if (choice == null || choice == "auto")
            choice = pref.viewer.orientation


        return when (choice) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun resolveCapturePointer(): Boolean {
        return connected && pref.input.capturePointer &&
               hasWindowFocus.isTrue && !viewerHelpIsVisible.isTrue
    }


    /**************************************************************************
     * Session observer
     **************************************************************************/
    private inner class RemoteSessionObserver : RemoteSession.Observer {

        override fun onConnecting() {
            state.postValue(State.Connecting)
        }

        override fun onConnected(vncClient: VncClient, messenger: Messenger) {
            launchMain {
                this@VncViewModel.client = vncClient
                this@VncViewModel.messenger = messenger
                state.value = State.Connected

                rememberLoginInfo()

                // Reset audio state on new connection - user must press the button
                audioStarted.value = false

                // Initial clipboard sync, slightly delayed to allow extended clipboard negotiations
                delay(1000L)
                sendClipboardText()
            }
        }

        override fun onDisconnected() {
            // Block until main thread is synchronised with disconnected state
            runBlocking(Dispatchers.Main) {
                state.value = State.Disconnected
                stopAudioStreaming()
                client = null
                messenger = null
            }
        }

        override fun onConnectionError(error: Throwable) {
            when (error) {
                is IOException -> disconnectReason.postValue(error.message)
                is UnsatisfiedLinkError -> disconnectReason.postValue("Missing native library")
            }
        }

        override fun onWakeOnLanBroadcastError(e: Throwable) {
            launchMain { Toast.makeText(app, "Wake-on-LAN: ${e.message}", Toast.LENGTH_LONG).show() }
        }


        /**************************** [VncClient.Observer] *******************/

        override fun getVncPassword(): String {
            return getLoginInfo(LoginInfo.Type.VNC_PASSWORD).password
        }

        override fun getVncCredentials(): UserCredential {
            return getLoginInfo(LoginInfo.Type.VNC_CREDENTIAL).let { UserCredential(it.username, it.password) }
        }

        override fun verifyVncServerCertificate(certificate: X509Certificate): Boolean {
            if (isCertificateTrusted(app, certificate))
                return true

            val title = "Unknown server certificate"
            val message = getUnknownCertificateMessage(certificate)
            if (confirmationRequest.getResponseFor(Pair(title, message))) {
                trustCertificate(app, certificate)
                return true
            }

            return false
        }

        override fun onFramebufferUpdated() {
            refreshFrameView()
            // Auto audio start removed - user must press toolbar audio button
        }

        override fun onCutTextReceived(text: String) {
            receiveClipboardText(text)
        }

        override fun onFramebufferSizeChanged(width: Int, height: Int) {
            launchMain {
                frameState.setFramebufferSize(width.toFloat(), height.toFloat())
            }
        }

        override fun onPointerMoved(x: Int, y: Int) {
            refreshFrameView()
        }

        override fun onBell() {
            if (pref.ui.bell) {
                Tones.notify(ToneGenerator.TONE_PROP_BEEP)
            }
        }

        override fun onQemuAudioBegin() = onQemuAudioBeginImpl()

        override fun onQemuAudioData(data: ByteArray) = onQemuAudioDataImpl(data)

        override fun onQemuAudioEnd() = onQemuAudioEndImpl()


        /**************************** [SshClient.Observer] *******************/

        override fun getKnownSshHostsFile() = getKnownHostsFile(app)

        override fun getSshPassword(): String {
            return getLoginInfo(LoginInfo.Type.SSH_PASSWORD).password
        }

        override fun getSshKeyPassword(): String {
            return getLoginInfo(LoginInfo.Type.SSH_KEY_PASSWORD).password
        }

        override fun confirmSshHostKeyWithUser(message: String, isNewHost: Boolean): Boolean {
            val titleRes = if (isNewHost) R.string.title_unknown_ssh_host else R.string.title_ssh_host_key_changed
            val title = app.getString(titleRes)
            return confirmationRequest.getResponseFor(Pair(title, message))
        }
    }
}