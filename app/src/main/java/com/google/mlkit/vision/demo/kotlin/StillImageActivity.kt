/*
 * Copyright 2020 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.mlkit.vision.demo.kotlin

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.util.Pair
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.annotation.KeepName
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.demo.BitmapUtils
import com.google.mlkit.vision.demo.GraphicOverlay
import com.google.mlkit.vision.demo.R
import com.google.mlkit.vision.demo.VisionImageProcessor
import com.google.mlkit.vision.demo.kotlin.labeldetector.LabelDetectorProcessor
import com.google.mlkit.vision.label.custom.CustomImageLabelerOptions
import java.io.IOException
import java.util.ArrayList
import kotlin.math.max

/** Activity demonstrating different image detector features with a still image from camera.  */
@KeepName
class StillImageActivity : AppCompatActivity() {
  private var preview: ImageView? = null
  private var graphicOverlay: GraphicOverlay? = null
  private var selectedMode =
          IMAGE_LABELING_CUSTOM
  private var selectedSize: String? =
    SIZE_SCREEN
  private var isLandScape = false
  private var imageUri: Uri? = null
  // Max width (portrait mode)
  private var imageMaxWidth = 0
  // Max height (portrait mode)
  private var imageMaxHeight = 0
  private var imageProcessor: VisionImageProcessor? = null
  override fun onCreate(savedInstanceState: Bundle?) {

    if (!allPermissionsGranted()) {
      runtimePermissions
    }

    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_still_image)
    findViewById<View>(R.id.select_image_button)
      .setOnClickListener { view: View ->
        // Menu for selecting either: a) take new photo b) select from existing
        val popup =
          PopupMenu(this@StillImageActivity, view)
        popup.setOnMenuItemClickListener { menuItem: MenuItem ->
          val itemId =
            menuItem.itemId
          if (itemId == R.id.select_images_from_local) {
            startChooseImageIntentForResult()
            return@setOnMenuItemClickListener true
          } else if (itemId == R.id.take_photo_using_camera) {
            startCameraIntentForResult()
            return@setOnMenuItemClickListener true
          }
          false
        }
        val inflater = popup.menuInflater
        inflater.inflate(R.menu.camera_button_menu, popup.menu)
        popup.show()
      }
    preview = findViewById(R.id.preview)
    graphicOverlay = findViewById(R.id.graphic_overlay)

    isLandScape =
      resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (savedInstanceState != null) {
      imageUri =
        savedInstanceState.getParcelable(KEY_IMAGE_URI)
      imageMaxWidth =
        savedInstanceState.getInt(KEY_IMAGE_MAX_WIDTH)
      imageMaxHeight =
        savedInstanceState.getInt(KEY_IMAGE_MAX_HEIGHT)
      selectedSize =
        savedInstanceState.getString(KEY_SELECTED_SIZE)
    }

    val rootView = findViewById<View>(R.id.root)
    rootView.viewTreeObserver.addOnGlobalLayoutListener(
      object : ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
          rootView.viewTreeObserver.removeOnGlobalLayoutListener(this)
          imageMaxWidth = rootView.width
          imageMaxHeight =
            rootView.height - findViewById<View>(R.id.control).height
          if (SIZE_SCREEN == selectedSize) {
            tryReloadAndDetectInImage()
          }
        }
      })
  }

  public override fun onResume() {
    super.onResume()
    Log.d(TAG, "onResume")
    createImageProcessor()
    tryReloadAndDetectInImage()
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.still_image_menu, menu)
    return true
  }

  public override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putParcelable(
      KEY_IMAGE_URI,
      imageUri
    )
    outState.putInt(
      KEY_IMAGE_MAX_WIDTH,
      imageMaxWidth
    )
    outState.putInt(
      KEY_IMAGE_MAX_HEIGHT,
      imageMaxHeight
    )
    outState.putString(
      KEY_SELECTED_SIZE,
      selectedSize
    )
  }

  private fun startCameraIntentForResult() { // Clean up last time's image
    imageUri = null
    preview!!.setImageBitmap(null)
    val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
    if (takePictureIntent.resolveActivity(packageManager) != null) {
      val values = ContentValues()
      values.put(MediaStore.Images.Media.TITLE, "New Picture")
      values.put(MediaStore.Images.Media.DESCRIPTION, "From Camera")
      imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
      takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
      startActivityForResult(
        takePictureIntent,
        REQUEST_IMAGE_CAPTURE
      )
    }
  }

  private fun startChooseImageIntentForResult() {
    val intent = Intent()
    intent.type = "image/*"
    intent.action = Intent.ACTION_GET_CONTENT
    startActivityForResult(
      Intent.createChooser(intent, "Select Picture"),
      REQUEST_CHOOSE_IMAGE
    )
  }

  override fun onActivityResult(
    requestCode: Int,
    resultCode: Int,
    data: Intent?
  ) {
    if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == Activity.RESULT_OK) {
      tryReloadAndDetectInImage()
    } else if (requestCode == REQUEST_CHOOSE_IMAGE && resultCode == Activity.RESULT_OK) {
      // In this case, imageUri is returned by the chooser, save it.
      imageUri = data!!.data
      tryReloadAndDetectInImage()
    } else {
      super.onActivityResult(requestCode, resultCode, data)
    }
  }

  private fun tryReloadAndDetectInImage() {
    Log.d(
      TAG,
      "Try reload and detect image"
    )
    try {
      if (imageUri == null) {
        return
      }

      if (SIZE_SCREEN == selectedSize && imageMaxWidth == 0) {
        // UI layout has not finished yet, will reload once it's ready.
        return
      }

      val imageBitmap = BitmapUtils.getBitmapFromContentUri(contentResolver, imageUri)
        ?: return
      // Clear the overlay first
      graphicOverlay!!.clear()
      // Get the dimensions of the image view
      val targetedSize = targetedWidthHeight
      // Determine how much to scale down the image
      val scaleFactor = max(
        imageBitmap.width.toFloat() / targetedSize.first.toFloat(),
        imageBitmap.height.toFloat() / targetedSize.second.toFloat()
      )
      val resizedBitmap = Bitmap.createScaledBitmap(
        imageBitmap,
        (imageBitmap.width / scaleFactor).toInt(),
        (imageBitmap.height / scaleFactor).toInt(),
        true
      )
      preview!!.setImageBitmap(resizedBitmap)
      if (imageProcessor != null) {
        graphicOverlay!!.setImageSourceInfo(
          resizedBitmap.width, resizedBitmap.height, /* isFlipped= */false
        )
        imageProcessor!!.processBitmap(resizedBitmap, graphicOverlay)
      } else {
        Log.e(
          TAG,
          "Null imageProcessor, please check adb logs for imageProcessor creation error"
        )
      }
    } catch (e: IOException) {
      Log.e(
        TAG,
        "Error retrieving saved image"
      )
      imageUri = null
    }
  }

  private val targetedWidthHeight: Pair<Int, Int>
    get() {
      val targetWidth: Int
      val targetHeight: Int
      when (selectedSize) {
        SIZE_SCREEN -> {
          targetWidth = imageMaxWidth
          targetHeight = imageMaxHeight
        }
        else -> throw IllegalStateException("Unknown size")
      }
      return Pair(targetWidth, targetHeight)
    }

  private fun createImageProcessor() {
    try {
      when (selectedMode) {
        IMAGE_LABELING_CUSTOM -> {
          Log.i(
            TAG,
            "Using Custom Image Label Detector Processor"
          )
          val localClassifier = LocalModel.Builder()
            .setAssetFilePath("custom_models/dog_classifier.tflite")
            .build()
          val customImageLabelerOptions =
            CustomImageLabelerOptions.Builder(localClassifier).build()
          imageProcessor =
            LabelDetectorProcessor(
              this,
              customImageLabelerOptions
            )
        }
      }
    } catch (e: Exception) {
      Log.e(
        TAG,
        "Can not create image processor: $selectedMode",
        e
      )
      Toast.makeText(
        applicationContext,
        "Can not create image processor: " + e.message,
        Toast.LENGTH_LONG
      )
        .show()
    }
  }

  private val requiredPermissions: Array<String?>
    get() = try {
      val info = this.packageManager
              .getPackageInfo(this.packageName, PackageManager.GET_PERMISSIONS)
      val ps = info.requestedPermissions
      if (ps != null && ps.isNotEmpty()) {
        ps
      } else {
        arrayOfNulls(0)
      }
    } catch (e: Exception) {
      arrayOfNulls(0)
    }

  private fun allPermissionsGranted(): Boolean {
    for (permission in requiredPermissions) {
      if (!StillImageActivity.isPermissionGranted(this, permission)) {
        return false
      }
    }
    return true
  }

  private val runtimePermissions: Unit
    get() {
      val allNeededPermissions: MutableList<String?> = ArrayList()
      for (permission in requiredPermissions) {
        if (!StillImageActivity.isPermissionGranted(this, permission)) {
          allNeededPermissions.add(permission)
        }
      }
      if (allNeededPermissions.isNotEmpty()) {
        ActivityCompat.requestPermissions(
                this,
                allNeededPermissions.toTypedArray(),
                StillImageActivity.PERMISSION_REQUESTS
        )
      }
    }

  companion object {
    private const val TAG = "StillImageActivity"
    private const val IMAGE_LABELING_CUSTOM = "Custom Image Labeling (Dogs)"
    private const val SIZE_SCREEN = "w:screen" // Match screen width
    private const val KEY_IMAGE_URI = "com.google.mlkit.vision.demo.KEY_IMAGE_URI"
    private const val KEY_IMAGE_MAX_WIDTH = "com.google.mlkit.vision.demo.KEY_IMAGE_MAX_WIDTH"
    private const val KEY_IMAGE_MAX_HEIGHT = "com.google.mlkit.vision.demo.KEY_IMAGE_MAX_HEIGHT"
    private const val KEY_SELECTED_SIZE = "com.google.mlkit.vision.demo.KEY_SELECTED_SIZE"
    private const val REQUEST_IMAGE_CAPTURE = 1001
    private const val REQUEST_CHOOSE_IMAGE = 1002
    private const val PERMISSION_REQUESTS = 1
    private fun isPermissionGranted(
            context: Context,
            permission: String?
    ): Boolean {
      if (ContextCompat.checkSelfPermission(context, permission!!)
              == PackageManager.PERMISSION_GRANTED
      ) {
        Log.i(TAG, "Permission granted: $permission")
        return true
      }
      Log.i(TAG, "Permission NOT granted: $permission")
      return false
    }
  }
}
