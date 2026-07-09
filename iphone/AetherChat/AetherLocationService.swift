import CoreLocation
import Foundation

@MainActor
final class AetherLocationService: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var locationContinuation: CheckedContinuation<CLLocation?, Never>?
    private var authorizationContinuation: CheckedContinuation<CLAuthorizationStatus, Never>?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    func localizeSearchQuery(_ query: String, originalUserText: String? = nil) async -> String {
        let userText = originalUserText ?? query
        guard Self.needsLocation(query) || Self.needsLocation(userText) else { return query }
        guard let location = await currentLocation() else {
            return query + " using my current city"
        }

        if let place = await reverseGeocode(location) {
            return Self.localizedQuery(query, originalUserText: userText, place: place)
        }

        let coordinate = location.coordinate
        let coordinateText = String(format: "%.5f, %.5f", coordinate.latitude, coordinate.longitude)
        return Self.localizedQuery(query, originalUserText: userText, place: coordinateText)
    }

    private func currentLocation() async -> CLLocation? {
        let status = await authorizedStatus()
        guard status == .authorizedWhenInUse || status == .authorizedAlways else {
            return nil
        }

        return await withCheckedContinuation { continuation in
            self.locationContinuation?.resume(returning: nil)
            self.locationContinuation = continuation
            manager.requestLocation()
        }
    }

    private func authorizedStatus() async -> CLAuthorizationStatus {
        let status = manager.authorizationStatus
        guard status == .notDetermined else { return status }

        return await withCheckedContinuation { continuation in
            self.authorizationContinuation?.resume(returning: manager.authorizationStatus)
            self.authorizationContinuation = continuation
            manager.requestWhenInUseAuthorization()
        }
    }

    private func reverseGeocode(_ location: CLLocation) async -> String? {
        guard let placemark = try? await CLGeocoder().reverseGeocodeLocation(location).first else {
            return nil
        }
        let city = placemark.locality ?? placemark.subLocality
        let region = placemark.administrativeArea
        let country = placemark.country
        return [city, region, country]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: ", ")
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        Task { @MainActor in
            locationContinuation?.resume(returning: locations.last)
            locationContinuation = nil
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in
            locationContinuation?.resume(returning: nil)
            locationContinuation = nil
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            authorizationContinuation?.resume(returning: status)
            authorizationContinuation = nil

            if status == .denied || status == .restricted {
                locationContinuation?.resume(returning: nil)
                locationContinuation = nil
            }
        }
    }

    static func needsLocation(_ query: String) -> Bool {
        let lc = query.lowercased()
        return lc.contains("near me")
            || lc.contains("nearby")
            || lc.contains("around me")
            || lc.contains("my area")
            || lc.contains("my location")
            || lc.contains("current location")
    }

    private static func replacingNearMe(in query: String, with place: String) -> String {
        query
            .replacingOccurrences(of: #"(?i)\bnear\s+me\b"#, with: "near \(place)", options: .regularExpression)
            .replacingOccurrences(of: #"(?i)\bnearby\b"#, with: "near \(place)", options: .regularExpression)
            .replacingOccurrences(of: #"(?i)\baround\s+me\b"#, with: "near \(place)", options: .regularExpression)
            .replacingOccurrences(of: #"(?i)\bmy\s+area\b"#, with: place, options: .regularExpression)
            .replacingOccurrences(of: #"(?i)\bmy\s+location\b"#, with: place, options: .regularExpression)
            .replacingOccurrences(of: #"(?i)\bcurrent\s+location\b"#, with: place, options: .regularExpression)
    }

    private static func localizedQuery(_ query: String, originalUserText: String, place: String) -> String {
        let replaced = replacingNearMe(in: query, with: place)
        let localized = replaced == query && needsLocation(originalUserText) ? "\(query) near \(place)" : replaced
        guard isLocalBusinessQuery(query) || isLocalBusinessQuery(originalUserText) else { return localized }

        return "\(localized) restaurants reviews open now local recommendations in \(place)"
    }

    private static func isLocalBusinessQuery(_ query: String) -> Bool {
        let lc = query.lowercased()
        return [
            "food",
            "restaurant",
            "restaurants",
            "spots",
            "mexican",
            "taco",
            "tacos",
            "coffee",
            "bar",
            "bars",
            "lunch",
            "dinner",
            "breakfast",
            "brunch"
        ].contains { lc.contains($0) }
    }
}
