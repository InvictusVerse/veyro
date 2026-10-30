// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/onboarding/OnboardingScreen.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veyro.core.network.LocalNetworkState
import com.veyro.ui.onboarding.components.PageIndicator

/**
 * First-run onboarding host (ADR-030): Welcome, Device name, Features, Pair, Done, in Sefirah's
 * pager with the page indicator and the primary button in the bottom bar.
 *
 * Veyro changes from Sefirah's pager: the current page lives in [OnboardingViewModel] and the pager
 * follows it (swiping is off), so rotation keeps the place and Back always means "the previous
 * page". Only the current page and the one being left are composed, so the Pair page starts its
 * discovery when it is reached and not while the user is still on Features. Optional pages get a
 * Skip action and the primary button's label follows the page.
 *
 * [viewModelFactory] builds the regular PairingViewModel the Pair page reuses.
 */
@Composable
public fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    viewModelFactory: ViewModelProvider.Factory,
    modifier: Modifier = Modifier,
    /** Android 17's local network permission (ADR-061): pairing finds nothing without it. */
    localNetwork: LocalNetworkState = LocalNetworkState.NOT_NEEDED,
    /** Asks for it, or opens App info when only that works. Needs the Activity. */
    onFixLocalNetwork: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(initialPage = state.stepIndex) { state.stepCount }

    LaunchedEffect(state.stepIndex) {
        if (pagerState.currentPage != state.stepIndex) {
            pagerState.animateScrollToPage(page = state.stepIndex)
        }
    }

    BackHandler(enabled = state.canGoBack) { viewModel.back() }

    // Grants happen in system screens; re-read whenever the user comes back.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshAccess()
        onPauseOrDispose { }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Spacer(modifier = Modifier.statusBarsPadding().padding(16.dp))
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 30.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PageIndicator(
                    modifier = Modifier.width(100.dp),
                    pageCount = state.stepCount,
                    selectedPage = state.stepIndex,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.step == OnboardingStep.FEATURES) {
                        TextButton(onClick = viewModel::skip) {
                            Text(
                                text = "Skip",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                    }
                    val skipsPairing = state.step == OnboardingStep.PAIR && state.paired == null
                    TextButton(
                        onClick = if (skipsPairing) viewModel::skip else viewModel::next,
                        enabled = state.step != OnboardingStep.DEVICE_NAME || state.deviceName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f),
                        ),
                        shape = RoundedCornerShape(size = 6.dp),
                    ) {
                        Text(
                            text = primaryLabel(state),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = false,
                beyondViewportPageCount = 0,
            ) { index ->
                val composeIt = index == state.stepIndex || index == pagerState.settledPage
                if (composeIt) {
                    when (OnboardingStep.entries[index]) {
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
                        // The Pair page belongs to the pairing flow and is hosted here unchanged.
                        OnboardingStep.PAIR -> PairPage(
                            viewModelFactory = viewModelFactory,
                            localNetwork = localNetwork,
                            onFixLocalNetwork = onFixLocalNetwork,
                        )
                        OnboardingStep.DONE -> DonePage(paired = state.paired)
                    }
                } else {
                    Box(Modifier.fillMaxSize())
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
