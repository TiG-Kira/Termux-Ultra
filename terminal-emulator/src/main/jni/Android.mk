LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c native_crash_handler.c
include $(BUILD_SHARED_LIBRARY)
