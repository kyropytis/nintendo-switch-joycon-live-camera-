package com.example.joyconcam.ui.main

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import com.example.joyconcam.data.ColorPalette
import com.example.joyconcam.data.IrResolution
import com.example.joyconcam.data.JoyConManager
import com.example.joyconcam.data.JoyConState
import kotlinx.coroutines.flow.StateFlow

class JoyConViewModel(application: Application) : AndroidViewModel(application) {
    val manager = JoyConManager(application.applicationContext)

    val state: StateFlow<JoyConState> = manager.state
    val latestFrame: StateFlow<Bitmap?> = manager.latestFrame

    fun startStream() {
        manager.start()
    }

    fun stopStream() {
        manager.stop()
    }

    fun setResolution(res: IrResolution) {
        manager.setResolution(res)
    }

    fun setPalette(palette: ColorPalette) {
        manager.setPalette(palette)
    }

    fun setExposure(us: Int) {
        manager.setExposure(us)
    }

    fun setDigitalGain(gain: Int) {
        manager.setDigitalGain(gain)
    }

    fun setLeds(leds: Int) {
        manager.setLeds(leds)
    }

    fun saveSnapshot(bitmap: Bitmap) {
        try {
            val filename = "JoyCon_IR_${System.currentTimeMillis()}.png"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/JoyConCam")
            }
            val uri = getApplication<Application>().contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
            if (uri != null) {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                android.widget.Toast.makeText(getApplication(), "Foto in Galerie gespeichert!", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(getApplication(), "Fehler: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCleared() {
        super.onCleared()
        manager.stop()
    }
}
