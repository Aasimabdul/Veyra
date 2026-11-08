# Derived from the cef-project examples/CMakeLists.txt (BSD-style licence).
# Copyright (c) 2017 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license that
# can be found in the LICENSE file.

#
# Shared resources. These are included by the individual executable targets so
# the paths must be relative to those directories.
#

if(OS_WINDOWS)
  # Shared Windows resources (version information, icons).
  set(SHARED_RESOURCES_SRCS
    ../shared/win/big.ico
    ../shared/win/resource.h
    ../shared/win/small.ico
    )

  # Manifest files used for executables.
  set(SHARED_EXE_MANIFEST ../shared/win/shared.exe.manifest)
  set(SHARED_COMPATIBILITY_MANIFEST ../shared/win/compatibility.manifest)
endif()

#
# Shared configuration.
#

# Support nice project/target organization in Visual Studio.
if(OS_WINDOWS)
  # Group target contents by directory.
  macro(SET_VEYRA_SOURCE_GROUPS srcs)
    foreach(FILE ${srcs})
      # Get the absolute directory path.
      get_filename_component(ABS_FILE "${FILE}" ABSOLUTE)
      get_filename_component(PARENT_DIR "${ABS_FILE}" DIRECTORY)

      # Remove the common directory prefix. What remains in the group.
      string(REPLACE "${CMAKE_SOURCE_DIR}/src/" "" GROUP "${PARENT_DIR}")

      # Convert to Windows slashes.
      string(REPLACE "/" "\\" GROUP "${GROUP}")

      source_group("${GROUP}" FILES "${FILE}")
    endforeach()
  endmacro()
endif()

# Set the configuration-specific binary output directory.
if(GEN_NINJA OR GEN_MAKEFILES)
  # Force Ninja and Make to create a subdirectory named after the configuration.
  set(VEYRA_TARGET_OUT_DIR "${CMAKE_BINARY_DIR}/${CMAKE_BUILD_TYPE}")
else()
  set(VEYRA_TARGET_OUT_DIR "${CMAKE_BINARY_DIR}/$<CONFIGURATION>")
endif()
set(CEF_TARGET_OUT_DIR "${VEYRA_TARGET_OUT_DIR}")

# Set properties common to all targets.
macro(SET_VEYRA_PROPERTIES target)
  # Output all binaries to the configuration-specific build directory.
  set_target_properties(${target} PROPERTIES
                        ARCHIVE_OUTPUT_DIRECTORY "${VEYRA_TARGET_OUT_DIR}"
                        RUNTIME_OUTPUT_DIRECTORY "${VEYRA_TARGET_OUT_DIR}"
                        LIBRARY_OUTPUT_DIRECTORY "${VEYRA_TARGET_OUT_DIR}")

  if(OS_WINDOWS)
    set_property(TARGET ${target} PROPERTY FOLDER "veyra")
  endif()
endmacro()

# Set properties on a library target.
macro(SET_VEYRA_LIBRARY_TARGET_PROPERTIES target)
  SET_LIBRARY_TARGET_PROPERTIES(${target})
  SET_VEYRA_PROPERTIES(${target})
endmacro()

# Set properties on an executable target.
macro(SET_VEYRA_EXECUTABLE_TARGET_PROPERTIES target)
  SET_EXECUTABLE_TARGET_PROPERTIES(${target})
  SET_VEYRA_PROPERTIES(${target})

  if(OS_LINUX)
    # Set rpath so that libraries can be placed next to the executable.
    set_target_properties(${target} PROPERTIES INSTALL_RPATH "$ORIGIN")
    set_target_properties(${target} PROPERTIES BUILD_WITH_INSTALL_RPATH TRUE)
  elseif(OS_WINDOWS)
    # Add the custom Windows manifest files to the executable.
    add_custom_command(
      TARGET ${target}
      POST_BUILD
      COMMAND "mt.exe" -nologo
              -manifest \"${CMAKE_CURRENT_SOURCE_DIR}/${SHARED_EXE_MANIFEST}\"
                        \"${CMAKE_CURRENT_SOURCE_DIR}/${SHARED_COMPATIBILITY_MANIFEST}\"
              -outputresource:"${VEYRA_TARGET_OUT_DIR}/${target}.exe"\;\#1
      COMMENT "Adding manifest..."
      )
  endif()
endmacro()

#
# Target configuration.
#

# Static library shared by the executable target.
add_subdirectory(shared)

# Veyra executable target.
add_subdirectory(app)
