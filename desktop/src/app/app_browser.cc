// Copyright (c) 2017 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include <fstream>
#include <string>

#include "app/client.h"
#include "include/cef_command_line.h"
#include "include/cef_path_util.h"
#include "include/cef_task.h"
#include "include/wrapper/cef_closure_task.h"
#include "include/base/cef_callback.h"
#include "shared/app_factory.h"
#include "shared/browser_util.h"
#include "shared/client_manager.h"

namespace veyra {

namespace {

constexpr int kSmokeTimeoutMs = 45000;

// file:// URL of the Veyra start page shipped next to the executable.
std::string StartPageUrl(bool smoke) {
  CefString exe_dir;
  CefGetPath(PK_DIR_EXE, exe_dir);
  std::string dir = exe_dir.ToString();
  for (auto& c : dir) {
    if (c == '\\') c = '/';
  }
  std::string url = "file://";
  if (dir.empty() || dir[0] != '/') url += "/";  // Windows drive letter.
  url += dir + "/ui/home.html";
  if (smoke) url += "?smoke=1";
  return url;
}

void SmokeTimeout(std::string output_path) {
  if (!output_path.empty()) {
    std::ofstream out(output_path, std::ios::trunc);
    out << "FAIL|timeout waiting for rendered start page\n";
  }
  shared::ClientManager::GetInstance()->CloseAllBrowsers(true);
}

}  // namespace

// CefApp for the browser process.
class BrowserApp : public CefApp, public CefBrowserProcessHandler {
 public:
  BrowserApp() = default;
  BrowserApp(const BrowserApp&) = delete;
  BrowserApp& operator=(const BrowserApp&) = delete;

  // CefApp methods:
  CefRefPtr<CefBrowserProcessHandler> GetBrowserProcessHandler() override {
    return this;
  }

  // CefBrowserProcessHandler methods:
  void OnContextInitialized() override {
    auto cl = CefCommandLine::GetGlobalCommandLine();
    SmokeTest smoke;
    smoke.enabled = cl->HasSwitch("smoke-test");
    smoke.output_path = cl->GetSwitchValue("smoke-test-output").ToString();

    shared::CreateBrowser(new Client(smoke), StartPageUrl(smoke.enabled),
                          CefBrowserSettings());

    if (smoke.enabled) {
      CefPostDelayedTask(
          TID_UI,
          CefCreateClosureTask(base::BindOnce(&SmokeTimeout, smoke.output_path)),
          kSmokeTimeoutMs);
    }
  }

 private:
  IMPLEMENT_REFCOUNTING(BrowserApp);
};

}  // namespace veyra

namespace shared {

CefRefPtr<CefApp> CreateBrowserProcessApp() {
  return new veyra::BrowserApp();
}

}  // namespace shared
