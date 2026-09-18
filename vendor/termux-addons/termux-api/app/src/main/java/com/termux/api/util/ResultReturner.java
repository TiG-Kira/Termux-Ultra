package com.termux.api.util;

import android.app.Activity;
import android.app.IntentService;
import android.content.BroadcastReceiver;
import android.content.BroadcastReceiver.PendingResult;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;
import android.util.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public abstract class ResultReturner {

    /**
     * An extra intent parameter which specifies a linux abstract namespace socket address where output from the API
     * call should be written.
     */
    private static final String SOCKET_OUTPUT_EXTRA = "socket_output";

    /**
     * An extra intent parameter which specifies a linux abstract namespace socket address where input to the API call
     * can be read from.
     */
    private static final String SOCKET_INPUT_EXTRA = "socket_input";

    public interface ResultWriter {
        void writeResult(PrintWriter out) throws Exception;
    }

    /**
     * Possible subclass of {@link ResultWriter} when input is to be read from stdin.
     */
    public static abstract class WithInput implements ResultWriter {
        protected InputStream in;

        public void setInput(InputStream inputStream) throws Exception {
            this.in = inputStream;
        }
    }

    /**
     * Possible marker interface for a {@link ResultWriter} when input is to be read from stdin.
     */
    public static abstract class WithStringInput extends WithInput {
        protected String inputString;

        protected boolean trimInput() {
            return true;
        }

        @Override
        public final void setInput(InputStream inputStream) throws Exception {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int l;
            while ((l = inputStream.read(buffer)) > 0) {
                baos.write(buffer, 0, l);
            }
            inputString = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            if (trimInput()) inputString = inputString.trim();
        }
    }

    public static abstract class WithAncillaryFd implements ResultWriter {
        private int fd = -1;

        public final void setFd(int newFd) {
            fd = newFd;
        }

        public final int getFd() {
            return fd;
        }
    }

    public static abstract class ResultJsonWriter implements ResultWriter {
        @Override
        public final void writeResult(PrintWriter out) throws Exception {
            JsonWriter writer = new JsonWriter(out);
            writer.setIndent("  ");
            writeJson(writer);
            out.println(); // To add trailing newline.
        }

        public abstract void writeJson(JsonWriter out) throws Exception;
    }

    /**
     * Just tell termux-api.c that we are done.
     */
    public static void noteDone(BroadcastReceiver receiver, final Intent intent) {
        returnData(receiver, intent, null);
    }

    public static void copyIntentExtras(Intent origIntent, Intent newIntent) {
        newIntent.putExtra("api_method", origIntent.getStringExtra("api_method"));
        newIntent.putExtra(SOCKET_OUTPUT_EXTRA, origIntent.getStringExtra(SOCKET_OUTPUT_EXTRA));
        newIntent.putExtra(SOCKET_INPUT_EXTRA, origIntent.getStringExtra(SOCKET_INPUT_EXTRA));

    }

    /**
     * Run in a separate thread, unless the context is an IntentService.
     *
     * NOTE on BroadcastReceiver ordering:
     * On Android 14+ the termux-api C client invokes `am broadcast -n ...`, which is a
     * NON-ORDERED broadcast (Context.sendBroadcast, NOT sendOrderedBroadcast).  Calling
     * PendingResult.setResult*() on a non-ordered broadcast throws:
     *     RuntimeException: BroadcastReceiver trying to return result during a non-ordered broadcast
     * We therefore:
     *   • still call goAsync() (it is valid for both ordered and non-ordered receivers and
     *     simply lets us keep this receiver alive past onReceive while we talk to the socket),
     *   • record receiver.isOrderedBroadcast() BEFORE running off the main thread,
     *   • only call setResultCode() when we know the broadcast IS ordered, wrapping every
     *     call with a try/catch as a ROM-side defensive measure.
     */
    public static void returnData(Object context, final Intent intent, final ResultWriter resultWriter) {
        final BroadcastReceiver receiver = (context instanceof BroadcastReceiver) ? (BroadcastReceiver) context : null;
        // goAsync() is valid on both ordered and non-ordered broadcasts; it keeps the
        // BroadcastReceiver.onReceive dispatch alive while we do I/O on a worker thread.
        final PendingResult asyncResult = (receiver != null) ? receiver.goAsync() : null;
        final Activity activity = (context instanceof Activity) ? (Activity) context : null;
        // Must read isOrderedBroadcast() synchronously from the onReceive thread; the value
        // is undefined once we return from onReceive (i.e., after finish() or the outer
        // scope returns).  On older Android it is false for sendBroadcast; on newer
        // Android it still holds the correct value for the lifetime of the PendingResult.
        final boolean isOrdered = (receiver != null) && receiver.isOrderedBroadcast();

        final Runnable runnable = () -> {
            try {
                final ParcelFileDescriptor[] pfds = { null };
                try (LocalSocket outputSocket = new LocalSocket()) {
                    String outputSocketAdress = intent.getStringExtra(SOCKET_OUTPUT_EXTRA);
                    outputSocket.connect(new LocalSocketAddress(outputSocketAdress));
                    try (PrintWriter writer = new PrintWriter(outputSocket.getOutputStream())) {
                        if (resultWriter != null) {
                            if (resultWriter instanceof WithInput) {
                                try (LocalSocket inputSocket = new LocalSocket()) {
                                    String inputSocketAdress = intent.getStringExtra(SOCKET_INPUT_EXTRA);
                                    inputSocket.connect(new LocalSocketAddress(inputSocketAdress));
                                    ((WithInput) resultWriter).setInput(inputSocket.getInputStream());
                                    resultWriter.writeResult(writer);
                                }
                            } else {
                                resultWriter.writeResult(writer);
                            }
                            if(resultWriter instanceof WithAncillaryFd) {
                                int fd = ((WithAncillaryFd) resultWriter).getFd();
                                if (fd >= 0) {
                                    pfds[0] = ParcelFileDescriptor.adoptFd(fd);
                                    FileDescriptor[] fds = { pfds[0].getFileDescriptor() };
                                    outputSocket.setFileDescriptorsForSend(fds);
                                }
                            }
                        }
                    }
                }
                if(pfds[0] != null) {
                    pfds[0].close();
                }

                if (asyncResult != null) {
                    setResultCodeSafe(asyncResult, isOrdered, 0);
                } else if (activity != null) {
                    try { activity.setResult(Activity.RESULT_OK); } catch (Exception ignored) {
                        // Activity may be finishing / destroyed already; nothing we can do.
                    }
                }
            } catch (Exception e) {
                TermuxApiLogger.error("Error in ResultReturner", e);
                if (asyncResult != null) {
                    setResultCodeSafe(asyncResult, isOrdered, 1);
                } else if (activity != null) {
                    try { activity.setResult(Activity.RESULT_CANCELED); } catch (Exception ignored) { /* no-op */ }
                }
            } finally {
                if (asyncResult != null) {
                    // PendingResult.finish() is valid for both ordered and non-ordered
                    // broadcasts (it just marks the async onReceive work complete), but
                    // wrap defensively in case of vendor bugs.
                    try { asyncResult.finish(); } catch (RuntimeException re) {
                        TermuxApiLogger.info("PendingResult.finish() threw: " + re);
                    }
                } else if (activity != null) {
                    try { activity.finish(); } catch (Exception ignored) { /* already finishing */ }
                }
            }
        };

        if (context instanceof IntentService) {
            runnable.run();
        } else {
            new Thread(runnable).start();
        }
    }

    /**
     * Call PendingResult.setResultCode() only when the broadcast was ordered; for the
     * normal non-ordered path used by `am broadcast` setResult* is illegal and will throw.
     * Also wraps the call in a try/catch so a misbehaving custom ROM cannot crash the
     * whole API worker thread on us.
     *
     * Note: the termux-api C client does not read the result code; it waits for data on
     * the socket_output local socket and uses EOF as the "done" marker.  So even when we
     * are forced to skip setResultCode the command still completes correctly in the
     * calling terminal.
     */
    private static void setResultCodeSafe(PendingResult result, boolean isOrdered, int code) {
        if (result == null) return;
        if (!isOrdered) return;
        try {
            result.setResultCode(code);
        } catch (RuntimeException re) {
            // Expected when OEM ROMs report isOrderedBroadcast=true but the dispatch was
            // actually non-ordered.  Swallow and log a warning — result code is not
            // consumed by termux-api.c anyway.
            TermuxApiLogger.info("PendingResult.setResultCode(" + code + ") suppressed: " + re.getMessage());
        }
    }
}