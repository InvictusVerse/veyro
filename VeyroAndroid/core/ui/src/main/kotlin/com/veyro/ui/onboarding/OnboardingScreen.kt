// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/onboarding/OnboardingScreen.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.designsystem.components.VeyroButton
import com.veyro.core.designsystem.components.VeyroTextButton
import com.veyro.core.designsystem.theme.VeyroTextSecondary
import com.veyro.ui.onboarding.components.PageIndicator

/**
 * First-run onboarding host (ADR-030): Welcome, Device name, Features, Pair, Done.
 *
 * Veyro changes from Sefirah's pager: the current page lives in [OnboardingViewModel] rather than
 * in pager state, so rotation and process recreation keep the place and Back always means "the
 * previous page". Optional pages get a Skip action, and the primary button's label follows the
 * page.
 *
 * [viewModelFactory] builds the regular PairingViewModel the Pair page reuses.
 */
@Composable
public fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    viewModelFactory: ViewModelProvider.Factory,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler(enabled = state.canGoBack) { viewModel.back() }

    // Grants happen in system screens; re-read whenever the user comes back.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshAccess()
        onPauseOrDispose { }
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PageIndicator(pageCount = state.stepCount, selectedPage = state.stepIndex)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.step == OnboardingStep.FEATURES) {
                        VeyroTextButton(text = "Skip", onClick = viewModel::skip, color = VeyroTextSecondary)
                    }
                    val primary = primaryLabel(state)
                    VeyroButton(
                        text = primary,
                        onClick = if (state.step == OnboardingStep.PAIR && state.paired == null) {
                            viewModel::skip
                        } else {
                            viewModel::next
                        },
                        enabled = state.step != OnboardingStep.DEVICE_NAME || state.deviceName.isNotBlank(),
                        height = 44.dp,
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            AnimatedContent(
                targetState = state.step,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
                },
                label = "onboardingStep",
            ) { step ->
                when (step) {
                    OnboardingStep.WELCOME -> WelcomePage()
                    OnboardingStep.DEVICE_NAME -> DeviceNamePage(
                        name = state.deviceName,
                        onNameChange = viewModel::setDeviceNameDraft,
                        onDone = viewModel::next,
                    )
                    OnboardingStep.FEATURES -> FeaturesPage(
                        features = state.features,
                        onToggle = viewModel::setFeatureEnabled,
                        onAccessChanged = viewModel::refreshAccess,
                    )
                    OnboardingStep.PAIR -> PairPage(viewModelFactory = viewModelFactory)
                    OnboardingStep.DONE -> DonePage(paired = state.paired)
                }
            }
        }
    }
}

private fun primaryLabel(state: OnboardingViewModel.UiState): String = when (state.step) {
    OnboardingStep.WELCOME -> "Get started"
    OnboardingStep.DEVICE_NAME, OnboardingStep.FEATURES -> "Next"
    OnboardingStep.PAIR -> if (state.paired == null) "Skip for now" else "Next"
    OnboardingStep.DONE -> "Go to Home"
}
