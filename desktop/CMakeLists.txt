# Veyra Desktop (Windows x64/x86, Linux x64) — Chromium Embedded Framework (CEF) shell.
#
# Structure and CEF integration are derived from the official cef-project template
# (BSD-style licence, see third_party_licenses/cef-project-LICENSE.txt).

cmake_minimum_required(VERSION 3.21)

# Only generate Debug and Release configuration types.
set(CMAKE_CONFIGURATION_TYPES Debug Release)

project(VeyraDesktop VERSION 0.1.0 LANGUAGES C CXX)

# Use folders in the resulting project files.
set_property(GLOBAL PROPERTY USE_FOLDERS ON)

# Make/Ninja need an explicit build type for the CEF cmake modules.
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

#
# CEF configuration.
#

# CEF binary distribution to embed. Override with -DVEYRA_CEF_VERSION=... to bump the engine.
# Browse https://cef-builds.spotifycdn.com/index.html for available versions.
set(VEYRA_CEF_VERSION "144.0.6+g5f7e671+chromium-144.0.7559.59"
    CACHE STRING "CEF binary distribution version")
set(CEF_VERSION "${VEYRA_CEF_VERSION}")
set(CEF_CHANNEL "")

# Determine the platform. Only Windows (x86, x64) and Linux x64 are supported targets.
if("${CMAKE_SYSTEM_NAME}" STREQUAL "Linux")
  if(CMAKE_SIZEOF_VOID_P MATCHES 8 AND NOT "${CMAKE_SYSTEM_PROCESSOR}" MATCHES "^(arm|aarch64)")
    set(CEF_PLATFORM "linux64")
  else()
    message(FATAL_ERROR "Veyra Desktop supports Linux x86_64 only.")
  endif()
elseif("${CMAKE_SYSTEM_NAME}" STREQUAL "Windows")
  if("${CMAKE_CXX_COMPILER_ARCHITECTURE_ID}" STREQUAL "ARM64")
    message(FATAL_ERROR "Veyra Desktop does not target Windows ARM64 yet.")
  elseif(CMAKE_SIZEOF_VOID_P MATCHES 8)
    set(CEF_PLATFORM "windows64")
  else()
    set(CEF_PLATFORM "windows32")
  endif()
else()
  message(FATAL_ERROR "Unsupported platform: ${CMAKE_SYSTEM_NAME}")
endif()

# Add this project's cmake/ directory to the module path.
set(CMAKE_MODULE_PATH ${CMAKE_MODULE_PATH} "${CMAKE_CURRENT_SOURCE_DIR}/cmake")

# Download and extract the CEF binary distribution (executes DownloadCEF.cmake).
include(DownloadCEF)
DownloadCEF("${CEF_PLATFORM}" "${CEF_CHANNEL}" "${CEF_VERSION}"
            "${CMAKE_SOURCE_DIR}/third_party/cef")

# Add the CEF binary distribution's cmake/ directory to the module path.
set(CMAKE_MODULE_PATH ${CMAKE_MODULE_PATH} "${CEF_ROOT}/cmake")

# Load the CEF configuration (executes FindCEF.cmake).
find_package(CEF REQUIRED)

#
# Target configuration.
#

# Include the libcef_dll_wrapper target (executes libcef_dll/CMakeLists.txt).
add_subdirectory(${CEF_LIBCEF_DLL_WRAPPER_PATH} libcef_dll_wrapper)

# Allow includes relative to src/ (e.g. "shared/main.h").
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/src)

add_subdirectory(src)

# Display configuration settings.
PRINT_CEF_CONFIG()
