require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "react-native-nearby-places"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.description  = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]
  s.platforms    = { :ios => "15.1" }
  s.source       = { :git => package["repository"]["url"], :tag => "v#{s.version}" }
  s.source_files = "ios/**/*.{h,m,mm,cpp,swift}"
  s.frameworks   = "MapKit", "CoreLocation"

  # Handles all new-arch TurboModule + Codegen wiring: C++17 flags, RCT-Folly,
  # React-Codegen dependency, `RCT_NEW_ARCH_ENABLED` define. Available from
  # React Native 0.74+.
  install_modules_dependencies(s)
end
