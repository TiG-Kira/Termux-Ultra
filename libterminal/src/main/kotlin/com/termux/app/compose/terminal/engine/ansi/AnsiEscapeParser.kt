package com.termux.app.compose.terminal.engine.ansi

import kotlin.math.min

internal class AnsiEscapeParser(private val handler: TerminalActionHandler) {
    private var mEscapeState = ESC_NONE
    private val mArgs = IntArray(MAX_ESCAPE_PARAMETERS)
    private var mArgIndex = 0
    private var mArgsSubParamsBitSet = 0
    private val mOSCOrDeviceControlArgs = StringBuilder()
    private var mContinueSequence = false

    fun processCodePoint(b: Int) {
        when (mEscapeState) {
            ESC_APC -> { doApc(b); return }
            ESC_APC_ESCAPE -> { doApcEscape(b); return }
            ESC_P -> { doDeviceControl(b); return }
            ESC_P_ESCAPE -> { doDeviceControlEscape(b); return }
        }

        when (b) {
            0 -> return
            7 -> if (mEscapeState == ESC_OSC) doOsc(b) else handler.onBell()
            8 -> handler.onBackspace()
            9 -> handler.onHorizontalTab()
            10, 11, 12 -> handler.onLinefeed()
            13 -> handler.onCarriageReturn()
            14 -> handler.onShiftOut()
            15 -> handler.onShiftIn()
            24, 26 -> if (mEscapeState != ESC_NONE) {
                mEscapeState = ESC_NONE
                handler.onCodePoint(127)
            }
            27 -> {
                if (mEscapeState == ESC_OSC) doOsc(b) else startEscapeSequence()
            }
            else -> {
                mContinueSequence = false
                when (mEscapeState) {
                    ESC_NONE -> if (b >= 32) handler.onCodePoint(b)
                    ESC -> doEsc(b)
                    ESC_POUND -> { handler.onEscCommand(ESC_POUND, b); finishSequence() }
                    ESC_SELECT_LEFT_PAREN -> { handler.onEscCommand(ESC_SELECT_LEFT_PAREN, b); finishSequence() }
                    ESC_SELECT_RIGHT_PAREN -> { handler.onEscCommand(ESC_SELECT_RIGHT_PAREN, b); finishSequence() }
                    ESC_CSI -> doCsi(b)
                    ESC_CSI_UNSUPPORTED_PARAMETER_BYTE,
                    ESC_CSI_UNSUPPORTED_INTERMEDIATE_BYTE -> doCsiUnsupportedParameterOrIntermediateByte(b)
                    ESC_CSI_QUESTIONMARK -> doCsiQuestionMark(b)
                    ESC_CSI_BIGGERTHAN -> doCsiBiggerThan(b)

                    ESC_CSI_EXCLAMATION,
                    ESC_CSI_DOLLAR,
                    ESC_CSI_DOUBLE_QUOTE,
                    ESC_CSI_SINGLE_QUOTE,
                    ESC_CSI_QUESTIONMARK_ARG_DOLLAR,
                    ESC_CSI_ARGS_SPACE,
                    ESC_CSI_ARGS_ASTERIX -> {
                        handler.onCsiCommand(mEscapeState, b, mArgs, mArgIndex + 1, mArgsSubParamsBitSet)
                    }

                    ESC_PERCENT -> { handler.onEscCommand(ESC_PERCENT, b); finishSequence() }
                    ESC_OSC -> doOsc(b)
                    ESC_OSC_ESC -> doOscEsc(b)
                    else -> unknownSequence(b)
                }
                if (!mContinueSequence) finishSequence()
            }
        }
    }

    private fun startEscapeSequence() {
        mEscapeState = ESC
        mArgIndex = 0
        mArgs.fill(-1)
        mArgsSubParamsBitSet = 0
    }

    private fun continueSequence(state: Int) {
        mEscapeState = state
        mContinueSequence = true
    }

    private fun finishSequence() {
        mEscapeState = ESC_NONE
    }

    private fun parseArg(b: Int) {
        when {
            b in '0'.code..'9'.code -> {
                if (mArgIndex < mArgs.size) {
                    val oldValue = mArgs[mArgIndex]
                    val thisDigit = b - '0'.code
                    mArgs[mArgIndex] = min(
                        if (oldValue >= 0) oldValue * 10 + thisDigit else thisDigit,
                        9999
                    )
                }
                continueSequence(mEscapeState)
            }
            b == ';'.code || b == ':'.code -> {
                if (mArgIndex + 1 < mArgs.size) {
                    mArgIndex++
                    if (b == ':'.code) {
                        mArgsSubParamsBitSet = mArgsSubParamsBitSet or (1 shl mArgIndex)
                    }
                }
                continueSequence(mEscapeState)
            }
            else -> unknownSequence(b)
        }
    }

    private fun doEsc(b: Int) {
        when (b.toChar()) {
            '#' -> continueSequence(ESC_POUND)
            '%' -> continueSequence(ESC_PERCENT)
            '(' -> continueSequence(ESC_SELECT_LEFT_PAREN)
            ')' -> continueSequence(ESC_SELECT_RIGHT_PAREN)
            'P' -> {
                mOSCOrDeviceControlArgs.setLength(0)
                continueSequence(ESC_P)
            }
            '[' -> continueSequence(ESC_CSI)
            ']' -> {
                mOSCOrDeviceControlArgs.setLength(0)
                continueSequence(ESC_OSC)
            }
            '_' -> continueSequence(ESC_APC)
            else -> handler.onEscCommand(ESC, b)
        }
    }

    private fun doCsi(b: Int) {
        when (b.toChar()) {
            '!' -> continueSequence(ESC_CSI_EXCLAMATION)
            '"' -> continueSequence(ESC_CSI_DOUBLE_QUOTE)
            '\'' -> continueSequence(ESC_CSI_SINGLE_QUOTE)
            '$' -> continueSequence(ESC_CSI_DOLLAR)
            '*' -> continueSequence(ESC_CSI_ARGS_ASTERIX)
            '?' -> continueSequence(ESC_CSI_QUESTIONMARK)
            '>' -> continueSequence(ESC_CSI_BIGGERTHAN)
            '<', '=' -> continueSequence(ESC_CSI_UNSUPPORTED_PARAMETER_BYTE)
            ' ' -> continueSequence(ESC_CSI_ARGS_SPACE)
            else -> handleCsiCommonArgs(b) {
                handler.onCsiCommand(ESC_CSI, b, mArgs, mArgIndex + 1, mArgsSubParamsBitSet)
            }
        }
    }

    private fun doCsiQuestionMark(b: Int) {
        when (b.toChar()) {
            '$' -> continueSequence(ESC_CSI_QUESTIONMARK_ARG_DOLLAR)
            else -> handleCsiCommonArgs(b) {
                handler.onCsiCommand(ESC_CSI_QUESTIONMARK, b, mArgs, mArgIndex + 1, mArgsSubParamsBitSet)
            }
        }
    }

    private fun doCsiBiggerThan(b: Int) {
        handleCsiCommonArgs(b) {
            handler.onCsiCommand(ESC_CSI_BIGGERTHAN, b, mArgs, mArgIndex + 1, mArgsSubParamsBitSet)
        }
    }

    private fun doCsiUnsupportedParameterOrIntermediateByte(b: Int) {
        when {
            mEscapeState == ESC_CSI_UNSUPPORTED_PARAMETER_BYTE && b in 0x30..0x3F -> continueSequence(ESC_CSI_UNSUPPORTED_PARAMETER_BYTE)
            b in 0x20..0x2F -> continueSequence(ESC_CSI_UNSUPPORTED_INTERMEDIATE_BYTE)
            b in 0x40..0x7E -> finishSequence()
            else -> unknownSequence(b)
        }
    }

    private fun doOsc(b: Int) {
        when (b) {
            7 -> doOscSetTextParameters("\u0007")
            27 -> continueSequence(ESC_OSC_ESC)
            else -> collectOSCArgs(b)
        }
    }

    private fun doOscEsc(b: Int) {
        when (b.toChar()) {
            '\\' -> doOscSetTextParameters("\u001b\\")
            else -> {
                collectOSCArgs(27)
                collectOSCArgs(b)
                continueSequence(ESC_OSC)
            }
        }
    }

    private fun collectOSCArgs(b: Int) {
        if (mOSCOrDeviceControlArgs.length < MAX_OSC_STRING_LENGTH) {
            mOSCOrDeviceControlArgs.appendCodePoint(b)
        }
        continueSequence(mEscapeState)
    }

    private fun doOscSetTextParameters(bellOrStringTerminator: String) {
        var value = -1
        var textParameter = ""
        for (i in 0 until mOSCOrDeviceControlArgs.length) {
            val b = mOSCOrDeviceControlArgs[i]
            if (b == ';') {
                textParameter = mOSCOrDeviceControlArgs.substring(i + 1)
                break
            } else if (b in '0'..'9') {
                value = (if (value < 0) 0 else value * 10) + (b.code - '0'.code)
            } else {
                unknownSequence(b.code)
                return
            }
        }
        handler.onOscCommand(value, textParameter, bellOrStringTerminator)
        finishSequence()
    }

    private fun doDeviceControl(b: Int) {
        when (b.toByte()) {
            '\\'.code.toByte() -> {
                handler.onDeviceControl(mOSCOrDeviceControlArgs.toString())
                finishSequence()
            }
            27.toByte() -> continueSequence(ESC_P_ESCAPE)
            else -> {
                if (mOSCOrDeviceControlArgs.length <= MAX_OSC_STRING_LENGTH) {
                    mOSCOrDeviceControlArgs.appendCodePoint(b)
                }
                continueSequence(mEscapeState)
            }
        }
    }

    private fun doDeviceControlEscape(b: Int) {
        if (b == '\\'.code) {
            handler.onDeviceControl(mOSCOrDeviceControlArgs.toString())
            finishSequence()
        } else {
            if (mOSCOrDeviceControlArgs.length + 2 <= MAX_OSC_STRING_LENGTH) {
                mOSCOrDeviceControlArgs.appendCodePoint(27)
                mOSCOrDeviceControlArgs.appendCodePoint(b)
            }
            continueSequence(ESC_P)
        }
    }

    private fun doApc(b: Int) {
        if (b == 27) continueSequence(ESC_APC_ESCAPE)
    }

    private fun doApcEscape(b: Int) {
        if (b == '\\'.code) finishSequence() else continueSequence(ESC_APC)
    }

    private fun unknownSequence(b: Int) {
        finishSequence()
    }

    private inline fun handleCsiCommonArgs(b: Int, fallback: () -> Unit) {
        val c = b.toChar()
        if (c in '0'..'9' || c == ';' || c == ':') {
            parseArg(b)
        } else {
            fallback()
        }
    }

    fun reset() {
        mEscapeState = ESC_NONE
        mArgIndex = 0
        mContinueSequence = false
        mArgs.fill(-1)
        mOSCOrDeviceControlArgs.setLength(0)
    }

    companion object {
        const val ESC_NONE = 0
        const val ESC = 1
        const val ESC_POUND = 2
        const val ESC_SELECT_LEFT_PAREN = 3
        const val ESC_SELECT_RIGHT_PAREN = 4
        const val ESC_CSI = 6
        const val ESC_CSI_QUESTIONMARK = 7
        const val ESC_CSI_DOLLAR = 8
        const val ESC_PERCENT = 9
        const val ESC_OSC = 10
        const val ESC_OSC_ESC = 11
        const val ESC_CSI_BIGGERTHAN = 12
        const val ESC_P = 13
        const val ESC_P_ESCAPE = 24
        const val ESC_CSI_QUESTIONMARK_ARG_DOLLAR = 14
        const val ESC_CSI_ARGS_SPACE = 15
        const val ESC_CSI_ARGS_ASTERIX = 16
        const val ESC_CSI_DOUBLE_QUOTE = 17
        const val ESC_CSI_SINGLE_QUOTE = 18
        const val ESC_CSI_EXCLAMATION = 19
        const val ESC_APC = 20
        const val ESC_APC_ESCAPE = 21
        const val ESC_CSI_UNSUPPORTED_PARAMETER_BYTE = 22
        const val ESC_CSI_UNSUPPORTED_INTERMEDIATE_BYTE = 23

        const val MAX_ESCAPE_PARAMETERS = 32
        const val MAX_OSC_STRING_LENGTH = 8192

        inline fun getArg(args: IntArray, index: Int, defaultValue: Int, treatZeroAsDefault: Boolean): Int {
            if (index >= args.size) return defaultValue
            val arg = args[index]
            return if (arg < 0 || (arg == 0 && treatZeroAsDefault)) defaultValue else arg
        }
    }
}