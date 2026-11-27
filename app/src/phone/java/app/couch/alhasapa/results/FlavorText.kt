package app.couch.alhasapa.results

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * The phone reads a picture's text with ML Kit (1.11.1), on the phone itself: Google Play services' own model, downloaded with the app
 * (the manifest asks for it), and no picture leaves the phone. The picture is made at most [MAX_SIDE] pixels on its long side first
 * (a camera's photo is far bigger than reading needs), turned the way the camera held it.
 */
object FlavorText : TextReader {
    private const val MAX_SIDE = 2048

    override fun read(context: Context, uri: Uri, done: (PhotoRead) -> Unit) {
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val side = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (side / sample > MAX_SIDE) sample *= 2
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: run { done(PhotoRead.Failed); return }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { t -> done(PhotoRead.Lines(t.textBlocks.flatMap { b -> b.lines.map { it.text } })) }
            .addOnFailureListener { e -> done(if (e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE) PhotoRead.NotReady else PhotoRead.Failed) }
            .addOnCompleteListener { recognizer.close(); bitmap.recycle() }
    }
}
