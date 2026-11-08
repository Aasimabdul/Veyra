# Derived from the cef-project examples/minimal/CMakeLists.txt (BSD-style licence).
# Copyright (c) 2017 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license that
# can be found in the LICENSE file.

set(VEYRA_SRCS
  app_browser.cc
  app_other.cc
  app_renderer.cc
  client.cc
  client.h
  main.cc
  )
set(VEYRA_SRCS_WINDOWS
  resource.h
  )
APPEND_PLATFORM_SOURCES(VEYRA_SRCS)

set(VEYRA_RESOURCES_SRCS_WINDOWS
  win/resource.rc
  )
APPEND_PLATFORM_SOURCES(VEYRA_RESOURCES_SRCS)

if(OS_WINDOWS)
  set(VEYRA_RESOURCES_SRCS ${VEYRA_RESOURCES_SRCS} ${SHARED_RESOURCES_SRCS})
endif()

set(VEYRA_TARGET "veyra")

if(OS_LINUX)
  add_executable(${VEYRA_TARGET} ${VEYRA_SRCS})
  SET_VEYRA_EXECUTABLE_TARGET_PROPERTIES(${VEYRA_TARGET})
  add_dependencies(${VEYRA_TARGET} shared)
  target_link_libraries(${VEYRA_TARGET} shared)

  # Ship the Veyra start page next to the executable.
  add_custom_command(TARGET ${VEYRA_TARGET} POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_directory
            "${CMAKE_SOURCE_DIR}/assets/ui" "${VEYRA_TARGET_OUT_DIR}/ui"
    VERBATIM)
endif()

if(OS_WINDOWS)
  set(VEYRA_SRCS ${VEYRA_SRCS} ${VEYRA_RESOURCES_SRCS})
  SET_VEYRA_SOURCE_GROUPS("${VEYRA_SRCS}")

  if(USE_SANDBOX)
    # With the sandbox enabled the application is a DLL launched by bootstrap.exe.
    add_library(${VEYRA_TARGET} SHARED ${VEYRA_SRCS})
    SET_VEYRA_LIBRARY_TARGET_PROPERTIES(${VEYRA_TARGET})
    COPY_SINGLE_FILE(${VEYRA_TARGET} ${CEF_BINARY_DIR}/bootstrap.exe ${VEYRA_TARGET_OUT_DIR}/${VEYRA_TARGET}.exe)
  else()
    add_executable(${VEYRA_TARGET} WIN32 ${VEYRA_SRCS})
    SET_VEYRA_EXECUTABLE_TARGET_PROPERTIES(${VEYRA_TARGET})
  endif()

  add_dependencies(${VEYRA_TARGET} shared)
  target_link_libraries(${VEYRA_TARGET} shared)

  add_custom_command(TARGET ${VEYRA_TARGET} POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_directory
            "${CMAKE_SOURCE_DIR}/assets/ui" "${VEYRA_TARGET_OUT_DIR}/ui"
    VERBATIM)
endif()
