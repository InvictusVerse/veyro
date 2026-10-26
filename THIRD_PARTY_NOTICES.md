# Third-party notices

Veyro is licensed under the GNU General Public License v3.0 (see `LICENSE`).

## Sefirah

Parts of Veyro are adapted from **Sefirah** by shrimqy, licensed under GPL-3.0.

- Windows: https://github.com/shrimqy/Sefirah (reference commit `6db524a`, 2026-10-17)
- Android: https://github.com/shrimqy/Sefirah-Android (reference commit `6596ffc`, 2026-10-15)

Every file containing adapted Sefirah code carries a header naming the original file. Modified
by the Veyro authors: renamed, restructured to Veyro's modules, and moved onto Veyro's
encrypted link protocol.

Copyright (C) shrimqy and Sefirah contributors.
Modifications copyright (C) Veyro authors.

Adapted files (Veyro path <- Sefirah path):

- `VeyroWindows/src/Veyro/Platforms/Windows/Calling/BluetoothCallingService.cs` <- `src/Sefirah/Platforms/Windows/Calling/PhoneLineService.cs`, `src/Sefirah/Platforms/Windows/Utilities/CallingFeatureUtils.cs` (Limited Access Feature token not copied)
- `VeyroWindows/src/Veyro/Platforms/Windows/Calling/BluetoothCall.cs` <- `src/Sefirah/Platforms/Windows/Calling/PhoneCall.cs`
- `VeyroWindows/src/Veyro/ViewModels/InCallViewModel.cs` <- `src/Sefirah/ViewModels/CallSessionViewModel.cs`, `src/Sefirah/ViewModels/CallWindowViewModel.cs`
- `VeyroWindows/src/Veyro/Services/CallWindowService.cs` <- `src/Sefirah/Features/CallFeature.cs`
- `VeyroWindows/src/Veyro/Views/CallWindow.cs` <- `src/Sefirah/Views/WindowViews/CallWindow.xaml.cs`
- `VeyroWindows/src/Veyro/UserControls/CallView.xaml` <- `src/Sefirah/UserControls/CallView.xaml`
- `VeyroWindows/src/Veyro/UserControls/CallView.xaml.cs` <- `src/Sefirah/UserControls/CallView.xaml.cs`
- `VeyroWindows/src/Veyro/Views/OnboardingPage.xaml` <- `src/Sefirah/Views/Onboarding/WelcomePage.xaml`
- `VeyroWindows/src/Veyro/Views/OnboardingPage.xaml.cs` <- `src/Sefirah/Views/Onboarding/WelcomePage.xaml.cs`
- `VeyroWindows/src/Veyro/UserControls/PairingView.xaml` <- `src/Sefirah/Views/Settings/DeviceDiscoveryPage.xaml`
- `VeyroWindows/src/Veyro/UserControls/PairingView.xaml.cs` <- `src/Sefirah/Views/Settings/DeviceDiscoveryPage.xaml.cs`
- `VeyroWindows/src/Veyro/Dialogs/ConnectionRequestDialog.xaml` <- `src/Sefirah/Dialogs/ConnectionRequestDialog.xaml`
- `VeyroWindows/src/Veyro/Dialogs/ConnectionRequestDialog.xaml.cs` <- `src/Sefirah/Dialogs/ConnectionRequestDialog.xaml.cs`, `src/Sefirah/ViewModels/Dialogs/ConnectionRequestViewModel.cs`

## Others

Dependency licences are listed by each package (Gradle / NuGet). Bundled skills under
`.kiro/skills` are not part of Veyro and are not distributed.
