import Foundation

enum WorkerTracker {
    private static let checkinURL = URL(string: "https://iitd-companion-worker.hellonitinbro.workers.dev/api/checkin")!

    static func checkin(kerberos: String) {
        let id = kerberos.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !id.isEmpty, id != "demo" else { return }

        Task.detached(priority: .background) {
            var req = URLRequest(url: checkinURL)
            req.httpMethod = "POST"
            req.timeoutInterval = 5
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) IITDCompanion-iOS", forHTTPHeaderField: "User-Agent")
            let payload: [String: Any] = ["kerberos_id": id, "platform": "ios"]
            req.httpBody = try? JSONSerialization.data(withJSONObject: payload)
            _ = try? await URLSession.shared.data(for: req)
        }
    }
}
