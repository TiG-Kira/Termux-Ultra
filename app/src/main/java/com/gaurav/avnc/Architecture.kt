/*
 * Copyright (c) 2021  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc

import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.model.db.MainDb
import com.gaurav.avnc.ui.prefs.PrefsActivity
import com.gaurav.avnc.ui.vnc.input.Dispatcher
import com.gaurav.avnc.ui.vnc.FrameState
import com.gaurav.avnc.ui.vnc.FrameView
import com.gaurav.avnc.ui.vnc.ConfirmationDialog
import com.gaurav.avnc.ui.vnc.LoginFragment
import com.gaurav.avnc.ui.vnc.VirtualKeys
import com.gaurav.avnc.ui.vnc.VncActivity
import com.gaurav.avnc.viewmodel.PrefsViewModel
import com.gaurav.avnc.viewmodel.VncViewModel
import com.gaurav.avnc.viewmodel.service.SshClient
import com.gaurav.avnc.session.Messenger
import com.gaurav.avnc.vnc.VncClient

/**
 * Architecture overview (trimmed for Termux integration).
 *
 * VNC UI
 * ======
 *
 * [VncActivity] is responsible for driving the connection to VNC server.
 *
 * - [FrameView] renders the VNC framebuffer on screen.
 * - [FrameState] maintains information related to framebuffer rendering,
 *   like zoom, pan etc.
 *
 * - See [Dispatcher] for overview of input handling.
 * - [VirtualKeys] are used for keys not normally found on Android Keyboards.
 *
 *
 * VNC Connection
 * ==============
 *
 * - Connection to VNC server is managed by [VncViewModel], using [VncClient].
 * - [VncClient] is a wrapper around native `rfbClient` from LibVNCClient.
 *
 * - [LoginFragment] is used to ask username & password from user.
 * - [SshClient] is used to create a SSH tunnel, which can be used for connection.
 * - [ConfirmationDialog] is used to verify unknown SSH hosts and X509 certs with user.
 * -
 * - [Messenger] is used to send events to VNC server.
 *
 *
 * Database
 * ========
 *
 * We use a Room Database, [MainDb], to save list of servers.
 * Servers are modeled by the [ServerProfile] entity.
 *
 *
 * Services
 * ========
 *
 * - SSH Tunnel ([SshClient])
 * - Settings ([PrefsActivity] / [PrefsViewModel])
 */
private fun avnc() {
}
