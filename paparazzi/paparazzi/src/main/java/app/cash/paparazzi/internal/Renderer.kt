/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.cash.paparazzi.internal

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Environment
import app.cash.paparazzi.Flags
import com.android.ide.common.resources.deprecated.FrameworkResources
import com.android.ide.common.resources.deprecated.ResourceItem
import com.android.ide.common.resources.deprecated.ResourceRepository
import com.android.io.FolderWrapper
import com.android.layoutlib.bridge.Bridge
import com.android.layoutlib.bridge.android.RenderParamsFlags
import com.android.layoutlib.bridge.impl.DelegateManager
import java.io.Closeable
import java.io.File
import java.util.Locale

/** View rendering. */
internal class Renderer(
  private val environment: Environment,
  private val layoutlibCallback: PaparazziCallback,
  private val logger: PaparazziLogger
) : Closeable {
  private var bridge: Bridge? = null
  private lateinit var sessionParamsBuilder: SessionParamsBuilder

  /** Initialize the bridge and the resource maps. */
  fun prepare(): SessionParamsBuilder {
    val platformDataResDir = File("${environment.platformDir}/data/res")
    val frameworkResources = FrameworkResources(FolderWrapper(platformDataResDir)).apply {
      loadResources()
      loadPublicResources(logger)
    }

    val projectResources = object : ResourceRepository(FolderWrapper(environment.resDir), false) {
      override fun createResourceItem(name: String): ResourceItem {
        return ResourceItem(name)
      }
    }
    projectResources.loadResources()

    sessionParamsBuilder = SessionParamsBuilder(
      layoutlibCallback = layoutlibCallback,
      logger = logger,
      frameworkResources = frameworkResources,
      projectResources = projectResources,
      assetRepository = PaparazziAssetRepository(environment.assetsDir)
    )
      .plusFlag(RenderParamsFlags.FLAG_DO_NOT_RENDER_ON_CREATE, true)
      .withTheme("AppTheme", true)

    val platformDataRoot = System.getProperty("paparazzi.platform.data.root")
      ?: throw RuntimeException("Missing system property for 'paparazzi.platform.data.root'")
    val platformDataDir = File(platformDataRoot, "data")
    val fontLocation = File(platformDataDir, "fonts")
    val nativeLibLocation = File(platformDataDir, getNativeLibDir())
    val icuLocation = File(platformDataDir, "icu" + File.separator + "icudt68l.dat")
    val buildProp = File(environment.platformDir, "build.prop")
    val attrs = File(platformDataResDir, "values" + File.separator + "attrs.xml")
    val systemProperties = (
      DeviceConfig.loadProperties(buildProp) +
        mapOf(
          // We want Choreographer.USE_FRAME_TIME to be false so it uses System_Delegate.nanoTime()
          "debug.choreographer.frametime" to "false"
        )
      ).let { props ->
      // Workaround for https://github.com/cashapp/paparazzi/issues/486:
      // When used in a project compiled with SDK 33, the known_codenames build prop is too long
      // for our 'old' version of SystemProperties to handle. The SystemProperties class was
      // updated in SDK 33 to handle this, but we need to wait until we hit a layoutlib build
      // which matches (likely EE). The Paparazzi plugin manually forces the Build.VERSION.CODENAME
      // value when needed.
      // TODO: remove this once we upgrade to a layoutlib version which contains SDK 33 changes
      val codenames = props["ro.build.version.known_codenames"]?.takeIf { it.length <= 91 }
      props + mapOf("ro.build.version.known_codenames" to codenames)
    }

    bridge = Bridge().apply {
      check(
        init(
          systemProperties,
          fontLocation,
          nativeLibLocation.path,
          icuLocation.path,
          DeviceConfig.getEnumMap(attrs),
          logger
        )
      ) { "Failed to init Bridge." }
    }
    Bridge.getLock()
      .lock()
    try {
      Bridge.setLog(logger)
    } finally {
      Bridge.getLock()
        .unlock()
    }

    return sessionParamsBuilder
  }

  private fun getNativeLibDir(): String {
    val osName = System.getProperty("os.name").toLowerCase(Locale.US)
    val osLabel = when {
      osName.startsWith("windows") -> "win"
      osName.startsWith("mac") -> {
        val osArch = System.getProperty("os.arch").lowercase(Locale.US)
        if (osArch.startsWith("x86")) "mac" else "mac-arm"
      }
      else -> "linux"
    }
    return "$osLabel/lib64"
  }

  override fun close() {
    bridge = null

    Gc.gc()

    dumpDelegates()
  }

  fun dumpDelegates() {
    if (System.getProperty(Flags.DEBUG_LINKED_OBJECTS) != null) {
      println("Objects still linked from the DelegateManager:")
      DelegateManager.dump(System.out)
    }
  }
}
