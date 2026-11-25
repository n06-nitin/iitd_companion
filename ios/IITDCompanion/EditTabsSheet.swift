import SwiftUI

private let dockSize = 4
private let dockPrefKey = "dock"

func loadDock() -> [Portal] {
    guard let raw = UserDefaults.standard.string(forKey: dockPrefKey) else { return Portal.defaultDock }
    var seen = Set<Portal>()
    let parsed = raw.split(separator: ",").compactMap { Portal(rawValue: String($0)) }
        .filter { Portal.pickable.contains($0) && seen.insert($0).inserted }
    return parsed.count == dockSize ? parsed : Portal.defaultDock
}

func saveDock(_ dock: [Portal]) {
    UserDefaults.standard.set(dock.map(\.rawValue).joined(separator: ","), forKey: dockPrefKey)
}

struct EditTabsSheet: View {
    let onSave: ([Portal]) -> Void
    let onClose: () -> Void

    @State private var picked: [Portal]

    init(current: [Portal], onSave: @escaping ([Portal]) -> Void, onClose: @escaping () -> Void) {
        self.onSave = onSave
        self.onClose = onClose
        _picked = State(initialValue: current)
    }

    var body: some View {
        let rest = Portal.pickable.filter { !picked.contains($0) }
        let canSave = picked.count == dockSize

        VStack(spacing: 0) {
            HStack(spacing: 10) {
                StatusPill(label: "Tabs · \(picked.count)/\(dockSize)", dot: canSave ? Bone : Caution)
                ClayIconButton(icon: "icon_close", description: "Close", action: onClose)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)

            ScrollView {
                VStack(spacing: 16) {
                    ClayCard {
                        VStack(spacing: 8) {
                            ForEach(Array(picked.enumerated()), id: \.element.id) { i, p in
                                TabEditRow(
                                    portal: p,
                                    selected: true,
                                    canMoveUp: i > 0,
                                    canMoveDown: i < picked.count - 1,
                                    onToggle: { picked.removeAll { $0 == p } },
                                    onMoveUp: { picked.swapAt(i, i - 1) },
                                    onMoveDown: { picked.swapAt(i, i + 1) }
                                )
                            }
                            ForEach(rest) { p in
                                TabEditRow(
                                    portal: p,
                                    selected: false,
                                    canMoveUp: false,
                                    canMoveDown: false,
                                    onToggle: { if picked.count < dockSize { picked.append(p) } },
                                    onMoveUp: {},
                                    onMoveDown: {}
                                )
                            }
                        }
                    }

                    Text("Tap to deselect and pick 4 tabs")
                        .m3(12, lineHeight: 16)
                        .foregroundStyle(Muted.color)
                        .multilineTextAlignment(.center)

                    ClayButton(text: "Save", color: Bone, contentColor: Ink, enabled: canSave) {
                        onSave(picked)
                    }

                    if picked != Portal.defaultDock {
                        Text("Reset")
                            .m3(13, weight: .medium, lineHeight: 18)
                            .foregroundStyle(Muted.color)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 6)
                            .contentShape(Rectangle())
                            .onTapGesture { picked = Portal.defaultDock }
                    }
                    Spacer().frame(height: 8)
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
            }
        }
        .background(ClayBackground().ignoresSafeArea())
    }
}

private struct TabEditRow: View {
    let portal: Portal
    let selected: Bool
    let canMoveUp: Bool
    let canMoveDown: Bool
    let onToggle: () -> Void
    let onMoveUp: () -> Void
    let onMoveDown: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(portal.icon).renderingMode(.template).resizable()
                .frame(width: 20, height: 20)
                .foregroundStyle((selected ? portal.accent : Muted).color)
            Text(portal.label)
                .m3(15, weight: selected ? .semibold : .regular, lineHeight: 20)
                .foregroundStyle((selected ? Bone : Muted).color)
            Spacer()
            if selected {
                Button(action: onMoveUp) {
                    Image(systemName: "chevron.up")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Bone.color)
                        .frame(width: 30, height: 30)
                        .opacity(canMoveUp ? 1 : 0.25)
                }
                .buttonStyle(.plain)
                .disabled(!canMoveUp)
                Button(action: onMoveDown) {
                    Image(systemName: "chevron.down")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Bone.color)
                        .frame(width: 30, height: 30)
                        .opacity(canMoveDown ? 1 : 0.25)
                }
                .buttonStyle(.plain)
                .disabled(!canMoveDown)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .clay(selected ? ClayWell : Clay, radius: 16, depth: selected ? 0 : 4, inset: selected)
        .contentShape(RoundedRectangle(cornerRadius: 16))
        .onTapGesture(perform: onToggle)
    }
}
