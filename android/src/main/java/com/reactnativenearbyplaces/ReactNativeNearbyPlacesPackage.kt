package com.reactnativenearbyplaces

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class ReactNativeNearbyPlacesPackage : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
    if (name == ReactNativeNearbyPlacesModule.NAME) {
      ReactNativeNearbyPlacesModule(reactContext)
    } else {
      null
    }

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider =
    ReactModuleInfoProvider {
      mapOf(
        ReactNativeNearbyPlacesModule.NAME to ReactModuleInfo(
          ReactNativeNearbyPlacesModule.NAME,
          ReactNativeNearbyPlacesModule::class.java.name,
          /* canOverrideExistingModule = */ false,
          /* needsEagerInit = */ false,
          /* hasConstants = */ false,
          /* isCxxModule = */ false,
          /* isTurboModule = */ true
        )
      )
    }
}
