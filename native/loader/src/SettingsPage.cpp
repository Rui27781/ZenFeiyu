#include "SettingsPage.h"

#include "PillSwitch.h"
#include "loader_settings.h"

#include <QFrame>
#include <QHBoxLayout>
#include <QLabel>
#include <QVBoxLayout>

#include <windows.h>

namespace loader {

namespace {
const char* kSettingsQss = R"qss(
    QFrame#toggleRow {
        background: rgba(38, 40, 46, 0.85);
        border: 1px solid #2c2e35;
        border-radius: 8px;
    }
    QLabel#toggleName {
        color: #dfe3ea;
        font-size: 13px;
        font-weight: 600;
    }
    QLabel#toggleDesc {
        color: #868c98;
        font-size: 12px;
    }
)qss";

// Push the HWID toggle into the loader process environment so that:
//   1. Native Bypass / Early Inject children inherit ZENFEIYU_HWID
//   2. The injected DLL (which reads this env once at config()) can honour it
// Normal inject into an already-running process cannot rewrite that process's
// environment; the DLL then falls back to its built-in default (enabled + wmi).
void apply_hwid_env(bool on) {
    if (on) {
        SetEnvironmentVariableW(L"ZENFEIYU_HWID", L"1");
        // Keep the safe default surface set. Users who need more can set
        // ZENFEIYU_HWID_SURFACES manually before launching the loader.
        if (GetEnvironmentVariableW(L"ZENFEIYU_HWID_SURFACES", nullptr, 0) == 0) {
            SetEnvironmentVariableW(L"ZENFEIYU_HWID_SURFACES", L"wmi");
        }
    } else {
        SetEnvironmentVariableW(L"ZENFEIYU_HWID", L"0");
    }
}
} // namespace

SettingsPage::SettingsPage(QWidget* parent)
        : QWidget(parent) {
    setStyleSheet(QString::fromUtf8(kSettingsQss));
    buildUi();
    // Apply whatever was last saved so Early Inject children see the right value
    // even if the user never touches the switch this session.
    apply_hwid_env(loader_settings::is_hwid_spoof_enabled());
}

bool SettingsPage::hwidSpoofEnabled() const {
    return hwidSwitch_ && hwidSwitch_->isChecked();
}

QWidget* SettingsPage::makeToggleRow(const QString& name, const QString& desc,
                                     PillSwitch* sw, QWidget* parent) {
    auto* row = new QFrame(parent);
    row->setObjectName("toggleRow");
    row->setMinimumHeight(58);
    auto* rowLayout = new QHBoxLayout(row);
    rowLayout->setContentsMargins(14, 10, 14, 10);
    rowLayout->setSpacing(10);

    auto* names = new QVBoxLayout();
    auto* nameLabel = new QLabel(name, row);
    nameLabel->setObjectName("toggleName");
    auto* descLabel = new QLabel(desc, row);
    descLabel->setObjectName("toggleDesc");
    descLabel->setWordWrap(true);
    names->addWidget(nameLabel);
    names->addWidget(descLabel);

    rowLayout->addLayout(names, 1);
    rowLayout->addWidget(sw, 0, Qt::AlignVCenter);
    return row;
}

void SettingsPage::buildUi() {
    auto* col = new QVBoxLayout(this);
    col->setContentsMargins(18, 18, 18, 18);
    col->setSpacing(10);

    auto* title = new QLabel(QStringLiteral("Settings"), this);
    title->setObjectName("title");
    title->setAlignment(Qt::AlignLeft);
    title->setStyleSheet(QStringLiteral("font-size:18px;font-weight:600;color:#ffffff;"));
    col->addWidget(title);

    // ---- HWID Spoof ----
    // The real work lives inside the injected DLL (WMI vtable, optional net/reg
    // surfaces). This toggle writes settings.json and sets ZENFEIYU_HWID so that
    // Native Bypass / Early Inject children inherit the preference. Default ON
    // matches the DLL's own safe default.
    hwidSwitch_ = new PillSwitch(this);
    hwidSwitch_->setChecked(loader_settings::is_hwid_spoof_enabled());
    connect(hwidSwitch_, &PillSwitch::toggled, this, [](bool on) {
        loader_settings::save_value(loader_settings::kKeyHwidSpoof(), on);
        apply_hwid_env(on);
    });
    col->addWidget(makeToggleRow(
        QStringLiteral("HWID Spoof"),
        QStringLiteral("Enables the injected HWID spoof (WMI vtable by default: "
                       "UUID / disk / board serial). Takes full effect on Native "
                       "Bypass launches; for already-running instances the DLL "
                       "uses its built-in default. Default ON."),
        hwidSwitch_, this));

    // Native Bypass
    nativeBypassSwitch_ = new PillSwitch(this);
    nativeBypassSwitch_->setChecked(loader_settings::is_native_bypass_enabled());
    connect(nativeBypassSwitch_, &PillSwitch::toggled, this, [](bool on) {
        loader_settings::save_value(loader_settings::kKeyNativeBypass(), on);
    });
    col->addWidget(makeToggleRow(
        QStringLiteral("Native Bypass"),
        QStringLiteral("Auto: as soon as a Minecraft instance shows up, restart "
                       "it suspended from its own command line, map the DLL in "
                       "before it resumes, then let it go. The game window reopens "
                       "and you rejoin the server. Clicking Inject does the same "
                       "on demand. Default OFF."),
        nativeBypassSwitch_, this));

    col->addStretch(1);
}

} // namespace loader
