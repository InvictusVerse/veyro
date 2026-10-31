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
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
 * First-run onboarding host (ADR-030). It shows the five steps Welcome, Device name, Features,
 * Pair and Done in a pager, with the progress indicator and the primary action in a bottom bar.
 *
 * The current step lives in [OnboardingViewModel]; the pager only follows it and cannot be
 * swiped, so rotation keeps the place and Back always returns to the previous step. Just the
 * current step and the one being left are composed, which means the Pair step starts its
 * discovery when it is reached and not while the user is still choosing features. The Features
 * step has a Skip action and the primary button label follows the step.
 *
 * [viewModelFactory] builds the regular PairingViewModel that the Pair step reuses.
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

    // The view model is the source of truth for the step; scroll the pager to match it.
    LaunchedEffect(state.stepIndex) {
        if (pagerState.currentPage != state.stepIndex) {
            pagerState.animateScrollToPage(state.stepIndex)
        }
    }

    BackHandler(enabled = state.canGoBack) { viewModel.back() }

    // Permissions are granted in system screens, so read them again whenever the user returns.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshAccess()
        onPauseOrDispose { }
    }

    Scaffold(
        modifier = modifier,
        topBar = { Spacer(Modifier.statusBarsPadding().padding(16.dp)) },
        bottomBar = {
            OnboardingBottomBar(
                state = state,
                onSkip = viewModel::skip,
                onNext = viewModel::next,
            )
        },
    ) { innerPadding ->
        OnboardingPager(
            pagerState = pagerState,
            state = state,
            viewModel = viewModel,
            viewModelFactory = viewModelFactory,
            localNetwork = localNetwork,
            onFixLocalNetwork = onFixLocalNetwork,
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
        )
    }
}

@Composable
private fun OnboardingBottomBar(
    state: OnboardingViewModel.UiState,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    // On the Pair step with nothing paired, the primary button leaves the step instead of advancing.
    val primaryLeavesPairing = state.step == OnboardingStep.PAIR && state.paired == null
    val labelStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 30.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PageIndicator(
            pageCount = state.stepCount,
            selectedPage = state.stepIndex,
            modifier = Modifier.width(100.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.step == OnboardingStep.FEATURES) {
                TextButton(onClick = onSkip) { Text("Skip", style = labelStyle) }
            }
            Button(
                onClick = if (primaryLeavesPairing) onSkip else onNext,
                enabled = state.step != OnboardingStep.DEVICE_NAME || state.deviceName.isNotBlank(),
                shape = RoundedCornerShape(6.dp),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f),
                ),
            ) {
                Text(primaryLabel(state), style = labelStyle)
            }
        }
    }
}

@Composable
private fun OnboardingPager(
    pagerState: PagerState,
    state: OnboardingViewModel.UiState,
    viewModel: OnboardingViewModel,
    viewModelFactory: ViewModelProvider.Factory,
    localNetwork: LocalNetworkState,
    onFixLocalNetwork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        userScrollEnabled = false,
        beyondViewportPageCount = 0,
    ) { index ->
        // Compose the target step and the step being left (for the scroll animation); leave the rest empty.
        val isVisibleStep = index == state.stepIndex || index == pagerState.settledPage
        if (!isVisibleStep) {
            Box(Modifier.fillMaxSize())
        } else {
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
                // The Pair step belongs to the pairing flow and is hosted here as it is.
                OnboardingStep.PAIR -> PairPage(
                    viewModelFactory = viewModelFactory,
                    localNetwork = localNetwork,
                    onFixLocalNetwork = onFixLocalNetwork,
                )
                OnboardingStep.DONE -> DonePage(paired = state.paired)
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
