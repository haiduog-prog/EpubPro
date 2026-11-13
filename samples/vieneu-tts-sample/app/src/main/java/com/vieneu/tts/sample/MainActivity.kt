package com.vieneu.tts.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vieneu.tts.sample.ui.VieNeuSampleScreen
import com.vieneu.tts.sample.ui.VieNeuSampleViewModel
import com.vieneu.tts.sample.ui.theme.VieNeuSampleTheme

/**
 * Activity chính của ứng dụng thử nghiệm VieNeu-TTS v3-Nano + `sea-g2p`.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            VieNeuSampleTheme {
                val viewModel: VieNeuSampleViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                VieNeuSampleScreen(
                    state = uiState,
                    onStartDownload = viewModel::startDownload,
                    onCancelDownload = viewModel::cancelDownload,
                    onLoadEngine = viewModel::loadEngine,
                    onUnloadEngine = viewModel::unloadEngine,
                    onSelectVoice = viewModel::selectVoice,
                    onInputTextChanged = viewModel::updateInputText,
                    onApplySampleText = viewModel::applySampleText,
                    onSpeedChanged = viewModel::updateSpeed,
                    onSelectQualityPreset = viewModel::selectQualityPreset,
                    onSelectThreads = viewModel::selectThreads,
                    onSpeak = viewModel::speak,
                    onStopSpeaking = viewModel::stopSpeaking,
                )
            }
        }
    }
}
