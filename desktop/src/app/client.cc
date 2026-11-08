// Copyright (c) 2017 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "app/client.h"

#include <fstream>

#include "include/wrapper/cef_helpers.h"
#include "shared/client_manager.h"
#include "shared/client_util.h"

namespace veyra {

namespace {
constexpr char kSmokeOk[] = "VEYRA_SMOKE_OK|";
}  // namespace

Client::Client(const SmokeTest& smoke) : smoke_(smoke) {}

void Client::OnTitleChange(CefRefPtr<CefBrowser> browser,
                           const CefString& title) {
  shared::OnTitleChange(browser, title);
  const std::string t = title.ToString();
  if (smoke_.enabled && t.rfind(kSmokeOk, 0) == 0) {
    // Title was set by script inside the shipped start page, which proves the
    // Chromium engine loaded the local page and executed JavaScript.
    FinishSmokeTest(t);
  }
}

void Client::OnLoadError(CefRefPtr<CefBrowser> browser,
                         CefRefPtr<CefFrame> frame,
                         ErrorCode errorCode,
                         const CefString& errorText,
                         const CefString& failedUrl) {
  if (smoke_.enabled && frame->IsMain() && errorCode != ERR_ABORTED) {
    FinishSmokeTest("FAIL|load error " + errorText.ToString() + " " +
                    failedUrl.ToString());
  }
}

void Client::FinishSmokeTest(const std::string& result) {
  CEF_REQUIRE_UI_THREAD();
  if (smoke_done_) return;
  smoke_done_ = true;
  if (!smoke_.output_path.empty()) {
    std::ofstream out(smoke_.output_path, std::ios::trunc);
    out << result << "\n";
  }
  shared::ClientManager::GetInstance()->CloseAllBrowsers(true);
}

void Client::OnAfterCreated(CefRefPtr<CefBrowser> browser) {
  shared::OnAfterCreated(browser);
}

bool Client::DoClose(CefRefPtr<CefBrowser> browser) {
  return shared::DoClose(browser);
}

void Client::OnBeforeClose(CefRefPtr<CefBrowser> browser) {
  return shared::OnBeforeClose(browser);
}

}  // namespace veyra
