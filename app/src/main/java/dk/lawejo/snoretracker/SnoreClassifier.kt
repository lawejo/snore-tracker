package dk.lawejo.snoretracker

import android.content.Context
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import java.io.Closeable

/** YAMNet on-device. Returns the score for each of its 521 sound classes, e.g. "Snoring". */
class SnoreClassifier(context: Context) : Closeable {
    private val classifier = AudioClassifier.createFromOptions(
        context,
        AudioClassifier.AudioClassifierOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("yamnet.tflite").build())
            .setRunningMode(RunningMode.AUDIO_CLIPS)
            .build(),
    )
    private val format = AudioData.AudioDataFormat.builder()
        .setNumOfChannels(1)
        .setSampleRate(Audio.SAMPLE_RATE.toFloat())
        .build()

    fun classify(samples: ShortArray): Map<String, Float> {
        val audio = AudioData.create(format, samples.size)
        audio.load(FloatArray(samples.size) { samples[it] / 32768f })
        return classifier.classify(audio).classificationResults().firstOrNull()
            ?.classifications()?.firstOrNull()
            ?.categories()?.associate { it.categoryName() to it.score() }
            .orEmpty()
    }

    override fun close() = classifier.close()
}
