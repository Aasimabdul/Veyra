#include <QApplication>
#include <QCloseEvent>
#include <QLineEdit>
#include <QMainWindow>
#include <QMessageBox>
#include <QProgressBar>
#include <QTabWidget>
#include <QToolBar>
#include <QUrl>
#include <QVBoxLayout>
#include <QWebEnginePage>
#include <QWebEngineView>
#include <QTimer>
#include <iostream>

class BrowserWindow final : public QMainWindow {
public:
    explicit BrowserWindow(bool smokeTest = false) : smokeTest_(smokeTest) {
        setWindowTitle(QStringLiteral("Veyra"));
        resize(1280, 800);
        setStyleSheet(R"(
            QMainWindow, QTabWidget::pane { background: #0f1a2b; color: #e8f0fb; }
            QToolBar { background: #111f33; border: 0; spacing: 8px; padding: 8px; }
            QToolButton { background: #182a42; color: #e8f0fb; border: 0; border-radius: 9px; padding: 8px 12px; }
            QLineEdit { background: #091321; color: #e8f0fb; border: 1px solid #263d59; border-radius: 10px; padding: 9px 12px; selection-background-color: #168ba8; }
            QTabBar::tab { background: #111f33; color: #8fa3bf; padding: 10px 18px; margin-right: 4px; border-radius: 8px; }
            QTabBar::tab:selected { background: #1b344d; color: #39d8ff; }
            QProgressBar { height: 2px; border: 0; background: #111f33; }
            QProgressBar::chunk { background: #39d8ff; }
        )");

        auto *toolbar = addToolBar(QStringLiteral("Navigation"));
        toolbar->setMovable(false);
        auto *back = toolbar->addAction(QStringLiteral("←"));
        auto *forward = toolbar->addAction(QStringLiteral("→"));
        auto *reload = toolbar->addAction(QStringLiteral("↻"));
        auto *home = toolbar->addAction(QStringLiteral("⌂"));
        address_ = new QLineEdit;
        address_->setPlaceholderText(QStringLiteral("Search or enter address"));
        address_->setClearButtonEnabled(true);
        toolbar->addWidget(address_);

        progress_ = new QProgressBar;
        progress_->setRange(0, 100);
        progress_->setTextVisible(false);
        progress_->setMaximumHeight(3);
        auto *tabs = new QTabWidget;
        tabs->setDocumentMode(true);
        tabs->setTabsClosable(false);
        setCentralWidget(tabs);
        auto *page = new QWebEngineView;
        tabs->addTab(page, QStringLiteral("New Tab"));
        connect(back, &QAction::triggered, page, &QWebEngineView::back);
        connect(forward, &QAction::triggered, page, &QWebEngineView::forward);
        connect(reload, &QAction::triggered, page, &QWebEngineView::reload);
        connect(home, &QAction::triggered, this, [page] { page->setUrl(QUrl(QStringLiteral("https://duckduckgo.com/"))); });
        connect(address_, &QLineEdit::returnPressed, this, [this, page] { navigate(page); });
        connect(page, &QWebEngineView::urlChanged, this, [this](const QUrl &url) {
            if (!address_->hasFocus()) address_->setText(url.toString());
        });
        connect(page, &QWebEngineView::titleChanged, this, [this, tabs, page](const QString &title) {
            setWindowTitle((title.isEmpty() ? QStringLiteral("New Tab") : title) + QStringLiteral(" — Veyra"));
            tabs->setTabText(tabs->indexOf(page), title.isEmpty() ? QStringLiteral("New Tab") : title.left(24));
            if (smokeTest_ && title == QStringLiteral("Veyra Desktop Smoke Test")) {
                std::cout << "SMOKE_TEST_PASS: rendered page title verified\n";
                QTimer::singleShot(0, qApp, &QApplication::quit);
            }
        });
        connect(page, &QWebEngineView::loadProgress, progress_, &QProgressBar::setValue);
        connect(page, &QWebEngineView::loadFinished, this, [this](bool ok) {
            if (smokeTest_ && !ok) {
                std::cerr << "SMOKE_TEST_FAIL: local HTML failed to load\n";
                QTimer::singleShot(0, qApp, [this] { qApp->exit(1); });
            }
        });
        if (smokeTest_) {
            page->setHtml(QStringLiteral("<!doctype html><html><head><title>Veyra Desktop Smoke Test</title></head><body>Veyra rendering engine OK</body></html>"));
            QTimer::singleShot(15000, qApp, [this] {
                std::cerr << "SMOKE_TEST_FAIL: timed out waiting for rendered title\n";
                qApp->exit(1);
            });
        } else {
            page->setUrl(QUrl(QStringLiteral("https://duckduckgo.com/")));
        }
    }

private:
    void navigate(QWebEngineView *page) {
        QString text = address_->text().trimmed();
        if (text.isEmpty()) return;
        QUrl url = QUrl::fromUserInput(text);
        if (url.scheme().isEmpty()) url.setScheme(QStringLiteral("https"));
        page->setUrl(url);
    }
    bool smokeTest_;
    QLineEdit *address_ = nullptr;
    QProgressBar *progress_ = nullptr;
};

int main(int argc, char *argv[]) {
    const bool smokeTest = argc > 1 && QString::fromLocal8Bit(argv[1]) == QStringLiteral("--smoke-test");
    QApplication app(argc, argv);
    BrowserWindow window(smokeTest);
    window.show();
    return app.exec();
}
