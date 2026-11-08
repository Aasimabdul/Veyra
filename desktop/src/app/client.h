// Copyright (c) 2017 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#ifndef VEYRA_APP_CLIENT_H_
#define VEYRA_APP_CLIENT_H_

#include <string>

#include "include/cef_client.h"

namespace veyra {

// Optional deterministic rendering check (`veyra --smoke-test`).
struct SmokeTest {
  bool enabled = false;
  std::string output_path;  // Result is written here when non-empty.
};

// Browser client: window title/lifecycle plus load/error reporting.
class Client : public CefClient,
               public CefDisplayHandler,
               public CefLifeSpanHandler,
               public CefLoadHandler {
 public:
  explicit Client(const SmokeTest& smoke);
  Client(const Client&) = delete;
  Client& operator=(const Client&) = delete;

  // CefClient methods:
  CefRefPtr<CefDisplayHandler> GetDisplayHandler() override { return this; }
  CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override { return this; }
  CefRefPtr<CefLoadHandler> GetLoadHandler() override { return this; }

  // CefDisplayHandler methods:
  void OnTitleChange(CefRefPtr<CefBrowser> browser,
                     const CefString& title) override;

  // CefLifeSpanHandler methods:
  void OnAfterCreated(CefRefPtr<CefBrowser> browser) override;
  bool DoClose(CefRefPtr<CefBrowser> browser) override;
  void OnBeforeClose(CefRefPtr<CefBrowser> browser) override;

  // CefLoadHandler methods:
  void OnLoadError(CefRefPtr<CefBrowser> browser,
                   CefRefPtr<CefFrame> frame,
                   ErrorCode errorCode,
                   const CefString& errorText,
                   const CefString& failedUrl) override;

 private:
  void FinishSmokeTest(const std::string& result);

  const SmokeTest smoke_;
  bool smoke_done_ = false;

  IMPLEMENT_REFCOUNTING(Client);
};

}  // namespace veyra

#endif  // VEYRA_APP_CLIENT_H_
