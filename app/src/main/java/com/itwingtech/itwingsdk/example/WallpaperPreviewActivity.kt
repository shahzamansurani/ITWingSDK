package com.itwingtech.itwingsdk.example

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.itwingtech.itwingsdk.example.databinding.ActivityWallpaperPreviewBinding

class WallpaperPreviewActivity : AppCompatActivity() {
    private val binding by lazy { ActivityWallpaperPreviewBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Wallpaper" }
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val video = intent.getBooleanExtra(EXTRA_VIDEO, false)

        binding.toolbar.toolbarBack.setOnClickListener { finish() }
        binding.toolbar.toolbarTitle.text = title
        binding.wallpaperMedia.render(url = url, video = video || url.endsWith(".mp4", true) || url.endsWith(".webm", true) || url.endsWith(".mov", true) || url.endsWith(".mp3", true) || url.endsWith(".wav", true) || url.endsWith(".ogg", true))
    }

    companion object {
        const val EXTRA_TITLE = "itwing_wallpaper_title"
        const val EXTRA_URL = "itwing_wallpaper_url"
        const val EXTRA_VIDEO = "itwing_wallpaper_video"
    }
}
