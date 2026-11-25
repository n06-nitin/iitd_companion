import Foundation
import Observation

struct DropStatus: Equatable {
    let drop: Bool
    let dropKey: String
}

@Observable @MainActor
final class DropStore {
    static let shared = DropStore()
    private(set) var status: DropStatus?

    fileprivate func update(_ newStatus: DropStatus) {
        if status != newStatus {
            status = newStatus
        }
    }
}

enum WorkerTracker {
    private static let checkinURL = URL(string: "https://iitd-companion-worker.hellonitinbro.workers.dev/api/checkin")!
    private static let dropStatusURL = "https://iitd-companion-worker.hellonitinbro.workers.dev/api/perks-drop"

    static func checkin(kerberos: String) {
        let id = kerberos.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !id.isEmpty, id != "demo" else {
            refreshDropStatus()
            return
        }

        Task.detached(priority: .background) {
            var req = URLRequest(url: checkinURL)
            req.httpMethod = "POST"
            req.timeoutInterval = 5
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) IITDCompanion-iOS", forHTTPHeaderField: "User-Agent")
            let payload: [String: Any] = ["kerberos_id": id, "platform": "ios"]
            req.httpBody = try? JSONSerialization.data(withJSONObject: payload)
            if let (data, resp) = try? await URLSession.shared.data(for: req),
               let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode) {
                await parseAndUpdateDrop(data)
            }
        }
    }

    static func refreshDropStatus() {
        Task.detached(priority: .background) {
            let ts = Int(Date().timeIntervalSince1970 * 1000)
            guard let url = URL(string: "\(dropStatusURL)?t=\(ts)") else { return }
            var req = URLRequest(url: url)
            req.timeoutInterval = 5
            req.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
            req.setValue("no-cache, no-store, must-revalidate", forHTTPHeaderField: "Cache-Control")
            req.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) IITDCompanion-iOS", forHTTPHeaderField: "User-Agent")
            if let (data, resp) = try? await URLSession.shared.data(for: req),
               let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode) {
                await parseAndUpdateDrop(data)
            }
        }
    }

    private static func parseAndUpdateDrop(_ data: Data) async {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let drop = obj["drop"] as? Bool else { return }
        let dropKey = obj["dropKey"] as? String ?? ""
        await MainActor.run {
            DropStore.shared.update(DropStatus(drop: drop, dropKey: dropKey))
        }
    }
}
