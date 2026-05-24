#import "ReactNativeNearbyPlaces.h"
#import <MapKit/MapKit.h>
#import <CoreLocation/CoreLocation.h>

#pragma mark - Category mapping

/**
 * Maps JS-facing category strings to `MKPointOfInterestCategory`. v0.x
 * supports the food categories that motivated the module; unsupported
 * names are silently dropped (an empty filter falls back to "all
 * categories" in MapKit).
 */
static MKPointOfInterestCategory _Nullable RNNPCategoryFromString(NSString *name) {
  static NSDictionary<NSString *, MKPointOfInterestCategory> *map = nil;
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    map = @{
      @"restaurant": MKPointOfInterestCategoryRestaurant,
      @"cafe": MKPointOfInterestCategoryCafe,
      @"bakery": MKPointOfInterestCategoryBakery,
      @"brewery": MKPointOfInterestCategoryBrewery,
      @"winery": MKPointOfInterestCategoryWinery,
      @"foodMarket": MKPointOfInterestCategoryFoodMarket,
      @"nightlife": MKPointOfInterestCategoryNightlife,
    };
  });
  return map[name];
}

/**
 * Reverse map: `MKPointOfInterestCategory` → JS string. Categories outside
 * v0.x's supported set return `nil`; callers see a missing field rather
 * than a surprise string.
 */
static NSString *_Nullable RNNPStringFromCategory(MKPointOfInterestCategory _Nullable cat) {
  if (cat == nil) return nil;
  static NSDictionary<MKPointOfInterestCategory, NSString *> *map = nil;
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    map = @{
      MKPointOfInterestCategoryRestaurant: @"restaurant",
      MKPointOfInterestCategoryCafe: @"cafe",
      MKPointOfInterestCategoryBakery: @"bakery",
      MKPointOfInterestCategoryBrewery: @"brewery",
      MKPointOfInterestCategoryWinery: @"winery",
      MKPointOfInterestCategoryFoodMarket: @"foodMarket",
      MKPointOfInterestCategoryNightlife: @"nightlife",
    };
  });
  return map[cat];
}

#pragma mark - Serialization

static NSDictionary *RNNPSerializeMapItem(MKMapItem *item) {
  MKPlacemark *p = item.placemark;
  NSMutableDictionary *out = [NSMutableDictionary dictionary];

  if (item.name) out[@"name"] = item.name;
  if (item.phoneNumber) out[@"phoneNumber"] = item.phoneNumber;
  if (item.url) out[@"url"] = item.url.absoluteString;

  NSString *category = RNNPStringFromCategory(item.pointOfInterestCategory);
  if (category) out[@"category"] = category;

  out[@"coordinate"] = @{
    @"latitude": @(p.coordinate.latitude),
    @"longitude": @(p.coordinate.longitude),
  };

  if (p.thoroughfare) out[@"thoroughfare"] = p.thoroughfare;
  if (p.subThoroughfare) out[@"subThoroughfare"] = p.subThoroughfare;
  if (p.locality) out[@"locality"] = p.locality;
  if (p.subLocality) out[@"subLocality"] = p.subLocality;
  if (p.administrativeArea) out[@"administrativeArea"] = p.administrativeArea;
  if (p.postalCode) out[@"postalCode"] = p.postalCode;
  if (p.ISOcountryCode) out[@"isoCountryCode"] = p.ISOcountryCode;
  if (p.country) out[@"country"] = p.country;

  return out;
}

#pragma mark - Module

@implementation ReactNativeNearbyPlaces

// Register under the JS-facing name expected by `TurboModuleRegistry.getEnforcing<Spec>('NativeNearbyPlaces')`
// in `src/NativeNearbyPlaces.ts`. Without the explicit name, RCT_EXPORT_MODULE
// would default to the Obj-C class name (`ReactNativeNearbyPlaces`) and the
// JS lookup would fail at runtime.
RCT_EXPORT_MODULE(NativeNearbyPlaces)

// TurboModule registration — required for new architecture.
- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeNearbyPlacesSpecJSI>(params);
}

- (void)search:(NSString *)query
       centerLatitude:(double)centerLatitude
      centerLongitude:(double)centerLongitude
       latitudeDelta:(double)latitudeDelta
      longitudeDelta:(double)longitudeDelta
         resultTypes:(NSArray *)resultTypes
             resolve:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject
{
  MKLocalSearchRequest *request = [[MKLocalSearchRequest alloc] init];
  request.naturalLanguageQuery = query;
  request.region = MKCoordinateRegionMake(
    CLLocationCoordinate2DMake(centerLatitude, centerLongitude),
    MKCoordinateSpanMake(latitudeDelta, longitudeDelta)
  );

  // resultTypes is a JS array of strings; pointOfInterest is the sane default.
  MKLocalSearchResultType rt = 0;
  for (NSString *t in resultTypes) {
    if ([t isEqualToString:@"pointOfInterest"]) rt |= MKLocalSearchResultTypePointOfInterest;
    else if ([t isEqualToString:@"address"]) rt |= MKLocalSearchResultTypeAddress;
  }
  request.resultTypes = (rt == 0) ? MKLocalSearchResultTypePointOfInterest : rt;

  MKLocalSearch *searchHandle = [[MKLocalSearch alloc] initWithRequest:request];
  [searchHandle startWithCompletionHandler:^(MKLocalSearchResponse * _Nullable response, NSError * _Nullable error) {
    if (error) {
      reject(@"E_NEARBY_PLACES_SEARCH", error.localizedDescription, error);
      return;
    }
    NSMutableArray *out = [NSMutableArray arrayWithCapacity:response.mapItems.count];
    for (MKMapItem *item in response.mapItems) {
      [out addObject:RNNPSerializeMapItem(item)];
    }
    resolve(out);
  }];
}

- (void)searchNearby:(double)latitude
           longitude:(double)longitude
        radiusMeters:(double)radiusMeters
          categories:(NSArray *)categories
             resolve:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject
{
  // Apple caps MKLocalPointsOfInterestRequest's radius at 50,000m; floor at 1m.
  CLLocationDistance radius = MIN(MAX(radiusMeters, 1), 50000);
  CLLocationCoordinate2D center = CLLocationCoordinate2DMake(latitude, longitude);

  MKLocalPointsOfInterestRequest *request =
    [[MKLocalPointsOfInterestRequest alloc] initWithCenterCoordinate:center radius:radius];

  if (categories.count > 0) {
    NSMutableArray<MKPointOfInterestCategory> *resolved = [NSMutableArray array];
    for (NSString *name in categories) {
      MKPointOfInterestCategory cat = RNNPCategoryFromString(name);
      if (cat) [resolved addObject:cat];
    }
    if (resolved.count > 0) {
      request.pointOfInterestFilter = [[MKPointOfInterestFilter alloc] initIncludingCategories:resolved];
    }
  }

  // MKLocalSearch has separate initializers for the two request types —
  // `MKLocalPointsOfInterestRequest` is NOT a subclass of `MKLocalSearchRequest`.
  MKLocalSearch *searchHandle = [[MKLocalSearch alloc] initWithPointsOfInterestRequest:request];
  [searchHandle startWithCompletionHandler:^(MKLocalSearchResponse * _Nullable response, NSError * _Nullable error) {
    if (error) {
      reject(@"E_NEARBY_PLACES_NEARBY", error.localizedDescription, error);
      return;
    }
    NSMutableArray *out = [NSMutableArray arrayWithCapacity:response.mapItems.count];
    for (MKMapItem *item in response.mapItems) {
      [out addObject:RNNPSerializeMapItem(item)];
    }
    resolve(out);
  }];
}

@end
