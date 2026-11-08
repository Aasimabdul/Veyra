# Derived from the cef-project examples/shared/CMakeLists.txt (BSD-style licence).
# Copyright (c) 2017 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license that
# can be found in the LICENSE file.

set(SHARED_COMMON_SRCS
  app_factory.h
  main_util.cc
  main_util.h
  )

set(SHARED_SRCS
  ${SHARED_COMMON_SRCS}
  client_manager.cc
  client_manager.h
  client_util.cc
  client_util.h
  browser_util.cc
  browser_util.h
  main.h
  resource_util.cc
  resource_util.h
  )
set(SHARED_SRCS_LINUX
  client_util_linux.cc
  main_linux.cc
  resource_util_linux.cc
  resource_util_posix.cc
  )
set(SHARED_SRCS_WINDOWS
  client_util_win.cc
  main_win.cc
  resource_util_win.cc
  )
APPEND_PLATFORM_SOURCES(SHARED_SRCS)

set(SHARED_TARGET "shared")

# Logical target used to link the libcef library.
ADD_LOGICAL_TARGET("libcef_lib" "${CEF_LIB_DEBUG}" "${CEF_LIB_RELEASE}")

if(OS_LINUX)
  add_library(${SHARED_TARGET} STATIC ${SHARED_SRCS})
  SET_VEYRA_LIBRARY_TARGET_PROPERTIES(${SHARED_TARGET})
  add_dependencies(${SHARED_TARGET} libcef_dll_wrapper)
  target_link_libraries(${SHARED_TARGET} libcef_lib libcef_dll_wrapper ${CEF_STANDARD_LIBS})

  # Copy CEF binaries and resources next to the executable.
  COPY_FILES("${SHARED_TARGET}" "${CEF_BINARY_FILES}" "${CEF_BINARY_DIR}" "${VEYRA_TARGET_OUT_DIR}")
  COPY_FILES("${SHARED_TARGET}" "${CEF_RESOURCE_FILES}" "${CEF_RESOURCE_DIR}" "${VEYRA_TARGET_OUT_DIR}")

  # Prints a message about SUID permissions for chrome-sandbox.
  SET_LINUX_SUID_PERMISSIONS("${SHARED_TARGET}" "${VEYRA_TARGET_OUT_DIR}/chrome-sandbox")
endif()

if(OS_WINDOWS)
  SET_VEYRA_SOURCE_GROUPS("${SHARED_SRCS}")

  add_library(${SHARED_TARGET} STATIC ${SHARED_SRCS})
  SET_VEYRA_LIBRARY_TARGET_PROPERTIES(${SHARED_TARGET})
  add_dependencies(${SHARED_TARGET} libcef_dll_wrapper)
  target_link_libraries(${SHARED_TARGET} libcef_lib libcef_dll_wrapper ${CEF_STANDARD_LIBS})

  if(USE_SANDBOX)
    # Set LPAC ACLs required for Windows sandbox support.
    SET_LPAC_ACLS("${SHARED_TARGET}")
  endif()

  COPY_FILES("${SHARED_TARGET}" "${CEF_BINARY_FILES}" "${CEF_BINARY_DIR}" "${VEYRA_TARGET_OUT_DIR}")
  COPY_FILES("${SHARED_TARGET}" "${CEF_RESOURCE_FILES}" "${CEF_RESOURCE_DIR}" "${VEYRA_TARGET_OUT_DIR}")
endif()
