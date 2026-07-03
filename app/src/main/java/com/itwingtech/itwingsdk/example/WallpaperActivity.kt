package com.itwingtech.itwingsdk.example

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.itwingtech.itwingsdk.example.databinding.ActivityWallpaperBinding
import com.itwingtech.itwingsdk.wallpapers.ITWingWallpaperItem

class WallpaperActivity : AppCompatActivity() {
    private val binding by lazy { ActivityWallpaperBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.toolbar.toolbarBack.setOnClickListener { finish() }
        binding.toolbar.toolbarTitle.text = "Wallpaper SDK Views"

        binding.rvWallpapers.setOnWallpaperClickListener(::openPreview)
        binding.rvTopTrend.setOnWallpaperClickListener(::openPreview)
    }

    private fun openPreview(wallpaper: ITWingWallpaperItem) {
        startActivity(
            Intent(this, WallpaperPreviewActivity::class.java)
                .putExtra(WallpaperPreviewActivity.EXTRA_TITLE, wallpaper.title)
                .putExtra(WallpaperPreviewActivity.EXTRA_URL, wallpaper.imageUrl)
        )
    }
}
