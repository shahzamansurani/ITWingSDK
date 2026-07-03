package com.itwingtech.itwingsdk.example

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.itwingtech.itwingsdk.example.databinding.ActivityMediaLibraryBinding
import com.itwingtech.itwingsdk.media.ITWingMediaItem

class MediaLibraryActivity : AppCompatActivity() {
    private val binding by lazy { ActivityMediaLibraryBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.toolbar.toolbarBack.setOnClickListener { finish() }
        binding.toolbar.toolbarTitle.text = "Ringtones & Videos"

        binding.ringtones.setOnMediaClickListener { item -> openPreview(item, false) }
        binding.ringtoneTrends.setOnMediaClickListener { item -> openPreview(item, false) }
        binding.videos.setOnMediaClickListener { item -> openPreview(item, true) }
        binding.videoTrends.setOnMediaClickListener { item -> openPreview(item, true) }
    }

    private fun openPreview(item: ITWingMediaItem, video: Boolean) {
        if (item.mediaUrl.isBlank()) {
            Toast.makeText(this, "Media URL is empty", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, WallpaperPreviewActivity::class.java)
                .putExtra(WallpaperPreviewActivity.EXTRA_TITLE, item.title)
                .putExtra(WallpaperPreviewActivity.EXTRA_URL, item.mediaUrl)
                .putExtra(WallpaperPreviewActivity.EXTRA_VIDEO, video)
        )
    }
}
