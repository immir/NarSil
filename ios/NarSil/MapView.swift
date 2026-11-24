import SwiftUI
import UIKit

/// Lets the UI ask the map view for things only it can do (the overview).
@MainActor
final class MapHandle {
    fileprivate weak var view: MapUIView?
    func toggleOverview() { view?.toggleOverview() }
}

/// Native, zoomable map of the whole level, drawn from the game's own glyphs or MicroChasm tiles (MapView.kt).
///  - tap an adjacent grid: step there; tap a distant one: ghost path, tap again to go
///  - long press: describe the grid
///  - swipe: by option, pan the map, or one step or a run; always answers aim and direction prompts
///  - pinch: zoom; two-finger drag: pan (the camera follows the player again once they move)
struct MapView: UIViewRepresentable {
    @ObservedObject var game: GameState
    let handle: MapHandle
    var swipeMode: Int
    var tiles: Bool
    var padShown: Bool

    func makeUIView(context: Context) -> MapUIView {
        let v = MapUIView(game: game)
        handle.view = v
        return v
    }

    func updateUIView(_ v: MapUIView, context: Context) {
        v.swipeMode = SwipeMode(rawValue: swipeMode) ?? .step
        v.tilesWanted = tiles
        v.padShown = padShown
        v.mapChanged()
    }
}

@MainActor
final class MapUIView: UIView {
    private let game: GameState
    private let painter = GlyphPainter()
    var swipeMode = SwipeMode.step
    /// With the d-pad on, a direction prompt is shown on its keys, not on the map.
    var padShown = false { didSet { if oldValue != padShown { setNeedsDisplay() } } }
    var tilesWanted = false { didSet { if oldValue != tilesWanted { setNeedsDisplay() } } }

    private let tileSheet: UIImage? = UIImage(contentsOfFile: GameLauncher.libDir.appendingPathComponent("tiles/microchasm/16x16.png").path)
    private var tileCache = [Int: UIImage]()
    /// The sheet tinted as Sil-Q tints it while the player rages: each pixel's luma becomes (1, 0.3, 0.3) of it.
    private lazy var rageSheet: CGImage? = {
        guard let src = tileSheet?.cgImage else { return nil }
        let w = src.width, h = src.height
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 4 * w,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue),
              let data = ctx.data else { return nil }
        ctx.draw(src, in: CGRect(x: 0, y: 0, width: w, height: h))
        let px = data.bindMemory(to: UInt8.self, capacity: 4 * w * h)
        for i in stride(from: 0, to: 4 * w * h, by: 4) {
            let luma = min(255, (299 * Int(px[i]) + 587 * Int(px[i + 1]) + 114 * Int(px[i + 2])) / 1000)
            px[i] = UInt8(luma)
            px[i + 1] = UInt8(luma * 3 / 10)
            px[i + 2] = UInt8(luma * 3 / 10)
        }
        return ctx.makeImage()
    }()
    private let tilePx: CGFloat = 16
    private var tilesOn: Bool { tilesWanted && tileSheet != nil }
    private var cellW: CGFloat { tilesOn ? painter.cellH : painter.cellW }
    private var cellH: CGFloat { painter.cellH }

    private static let defaultCell: CGFloat = 22, minCell: CGFloat = 8, maxCell: CGFloat = 64
    private var cellHeight = MapUIView.defaultCell { didSet { cellHeight = min(max(cellHeight, minCellLimit), Self.maxCell); painter.setCellHeight(cellHeight) } }
    private var minCellLimit = MapUIView.minCell
    private var overviewFrom: CGFloat?

    /// Grid coordinate shown at the view centre.
    private var camX: CGFloat = 0, camY: CGFloat = 0
    private var followPlayer = true
    private var lastPlayer: Point?
    private var selected: Point?
    private var pinchStart: CGFloat = 0
    private var panFocus = CGPoint.zero

    private var meta: [Int32] { game.meta }
    private var mapW: Int { Int(meta[NB.META_WIDTH]) }
    private var mapH: Int { Int(meta[NB.META_HEIGHT]) }
    private var player: Point { Point(x: Int(meta[NB.META_PLAYER_X]), y: Int(meta[NB.META_PLAYER_Y])) }

    init(game: GameState) {
        self.game = game
        super.init(frame: .zero)
        backgroundColor = .black
        isOpaque = true
        isAccessibilityElement = true
        accessibilityLabel = "Dungeon map. Tap a square to preview the path, tap it again to walk there. Long-press to describe what is there."
        clipsToBounds = true
        contentMode = .redraw
        painter.setCellHeight(cellHeight)

        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        let long = UILongPressGestureRecognizer(target: self, action: #selector(pressed(_:)))
        let swipe = UIPanGestureRecognizer(target: self, action: #selector(swiped(_:)))
        swipe.maximumNumberOfTouches = 1
        let drag = UIPanGestureRecognizer(target: self, action: #selector(dragged(_:)))
        drag.minimumNumberOfTouches = 2
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(pinched(_:)))
        [tap, long, swipe, drag, pinch].forEach(addGestureRecognizer)
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    // MARK: camera

    func toggleOverview() {
        if let from = overviewFrom { endOverview(from); return }
        guard mapW > 0, mapH > 0, bounds.width > 0, cellH > 0 else { return }
        overviewFrom = cellHeight
        let aspect = cellW / cellH
        let fit = min(bounds.height / CGFloat(mapH), bounds.width / (CGFloat(mapW) * aspect))
        minCellLimit = min(Self.minCell, fit)
        cellHeight = fit
        followPlayer = false
        camX = CGFloat(mapW) / 2
        camY = CGFloat(mapH) / 2
        setNeedsDisplay()
    }

    private func endOverview(_ from: CGFloat) {
        overviewFrom = nil
        minCellLimit = Self.minCell
        cellHeight = from
        followPlayer = true
        keepPlayerInView(snap: true)
        setNeedsDisplay()
    }

    /// The tracked foe as last drawn, and a brief red fill on its square when its health drops (no animation: on, then off).
    private var lastFoe: Foe?
    private var flash: Point?
    private static let flashFill = UIColor(red: 1, green: 60 / 255, blue: 50 / 255, alpha: 0.45)

    private func noteFoe() {
        let f = game.status.foe, was = lastFoe
        lastFoe = f
        guard let f, let was, f.name == was.name, f.healthPct < was.healthPct else { return }
        let cell = Point(x: f.x, y: f.y)
        flash = cell
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) { [weak self] in
            if self?.flash == cell { self?.flash = nil; self?.setNeedsDisplay() }
        }
    }

    /// Called whenever the game published a new map, path or prompt.
    func mapChanged() {
        noteFoe()
        painter.setPalette(game.palette)
        if let from = overviewFrom, let last = lastPlayer, player != last { endOverview(from) }
        if player != lastPlayer {
            let first = lastPlayer == nil
            lastPlayer = player
            followPlayer = true
            if selected == player { selected = nil }
            keepPlayerInView(snap: first)
        }
        setNeedsDisplay()
    }

    /// Recentre when the player leaves the middle half of the view (always, with `snap`).
    private func keepPlayerInView(snap: Bool) {
        guard followPlayer, cellW > 0, bounds.width > 0 else { return }
        let p = player
        let halfW = bounds.width / cellW / 2, halfH = bounds.height / cellH / 2
        let outside = abs(CGFloat(p.x) + 0.5 - camX) > halfW * 0.5 || abs(CGFloat(p.y) + 0.5 - camY) > halfH * 0.5
        if outside || snap { camX = CGFloat(p.x) + 0.5; camY = CGFloat(p.y) + 0.5 }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        keepPlayerInView(snap: true)
    }

    private var originX: CGFloat { bounds.width / 2 - camX * cellW }
    private var originY: CGFloat { bounds.height / 2 - camY * cellH }

    private func grid(at p: CGPoint) -> Point? {
        let x = Int(floor((p.x - originX) / cellW)), y = Int(floor((p.y - originY) / cellH))
        return (0..<mapW).contains(x) && (0..<mapH).contains(y) ? Point(x: x, y: y) : nil
    }

    // MARK: drawing

    private func cellRect(_ x: Int, _ y: Int, ox: CGFloat, oy: CGFloat) -> CGRect {
        CGRect(x: ox + CGFloat(x) * cellW, y: oy + CGFloat(y) * cellH, width: cellW, height: cellH)
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        ctx.setFillColor(UIColor.black.cgColor)
        ctx.fill(bounds)
        let w = mapW, h = mapH
        guard w > 0, h > 0, !game.palette.isEmpty, game.map.count >= NB.MAP_CELL_INTS * w * h else { return }
        let cw = cellW, ch = cellH, ox = originX, oy = originY
        let x0 = max(0, Int(floor(-ox / cw))), y0 = max(0, Int(floor(-oy / ch)))
        let x1 = min(w - 1, Int(floor((bounds.width - ox) / cw))), y1 = min(h - 1, Int(floor((bounds.height - oy) / ch)))
        guard x0 <= x1, y0 <= y1 else { return }
        let map = game.map
        let sheet = tilesOn
        ctx.interpolationQuality = .none
        for y in y0...y1 {
            for x in x0...x1 {
                let i = NB.MAP_CELL_INTS * (y * w + x)
                let tile = Int(map[i + 2])
                if sheet && tile >= 0 {
                    let r = cellRect(x, y, ox: ox, oy: oy)
                    /* Tiles are transparent: the terrain goes beneath, the alert / glow marker on top. */
                    let bg = Int(map[i + 3])
                    let flags = Int(map[i + 4])
                    let rage = flags & NB.TILE_RAGE != 0
                    if bg >= 0 && bg != tile { drawTile(bg, in: r, rage: rage) }
                    drawTile(tile, in: r, rage: rage)
                    if flags & NB.TILE_ALERT != 0 { drawTile(Int(meta[NB.META_TILE_ALERT]), in: r, rage: rage) }
                    if flags & NB.TILE_GLOW != 0 { drawTile(Int(meta[NB.META_TILE_GLOW]), in: r, rage: rage) }
                } else {
                    let effect = (Int(map[i + 1]) >> 16) & (NB.CELL_ANIM | NB.CELL_TRAIL | NB.CELL_MARK)
                    if effect != 0 { drawEffect(ctx, effect: effect, attr: Int(map[i + 1]) & 0xFF, bgTile: Int(map[i + 3]), sheet: sheet, in: cellRect(x, y, ox: ox, oy: oy)) }
                    painter.drawCell(ctx, x: ox + CGFloat(x) * cw, y: oy + CGFloat(y) * ch, glyph: Int(map[i]), attr: Int(map[i + 1]) & 0xFFFF)
                }
            }
        }
        drawOverlays(ctx, ox: ox, oy: oy)
    }

    /// A spell, missile or hit mark in flight: the terrain beneath (tile mode), and a soft glow in the effect's colour behind its glyph.
    private func drawEffect(_ ctx: CGContext, effect: Int, attr: Int, bgTile: Int, sheet: Bool, in r: CGRect) {
        if sheet && bgTile >= 0 { drawTile(bgTile, in: r) }
        if effect & NB.CELL_MARK != 0 { return }
        let head = effect & NB.CELL_ANIM != 0
        let rgb = game.colour(attr % 32)
        ctx.setFillColor(UIColor(red: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255, blue: CGFloat(rgb & 0xFF) / 255, alpha: head ? 0.59 : 0.24).cgColor)
        let d = min(r.width, r.height) * (head ? 1.2 : 0.8)
        ctx.fillEllipse(in: CGRect(x: r.midX - d / 2, y: r.midY - d / 2, width: d, height: d))
    }

    /// Tile `t` is (row << 8) | column on the sheet; `rage` draws it from the red-tinted sheet.
    private func drawTile(_ t: Int, in r: CGRect, rage: Bool = false) {
        guard t >= 0, let sheet = rage ? (rageSheet ?? tileSheet?.cgImage) : tileSheet?.cgImage else { return }
        let key = rage ? t | (1 << 20) : t
        let image: UIImage
        if let cached = tileCache[key] { image = cached } else {
            let src = CGRect(x: CGFloat(t & 0xFF) * tilePx, y: CGFloat(t >> 8) * tilePx, width: tilePx, height: tilePx)
            guard let part = sheet.cropping(to: src) else { return }
            image = UIImage(cgImage: part)
            tileCache[key] = image
        }
        image.draw(in: r)
    }

    private static let pathFill = UIColor(red: 1, green: 200 / 255, blue: 60 / 255, alpha: 90 / 255)
    private static let gold = UIColor(red: 1, green: 200 / 255, blue: 60 / 255, alpha: 1)
    private static let ring = UIColor(red: 1, green: 90 / 255, blue: 80 / 255, alpha: 1)
    private static let you = UIColor(red: 120 / 255, green: 200 / 255, blue: 1, alpha: 140 / 255)

    private func drawOverlays(_ ctx: CGContext, ox: CGFloat, oy: CGFloat) {
        func fill(_ c: UIColor, _ x: Int, _ y: Int) { ctx.setFillColor(c.cgColor); ctx.fill(cellRect(x, y, ox: ox, oy: oy)) }
        func outline(_ c: UIColor, _ x: Int, _ y: Int, width: CGFloat = 2) {
            ctx.setStrokeColor(c.cgColor); ctx.setLineWidth(width); ctx.stroke(cellRect(x, y, ox: ox, oy: oy))
        }
        let prompt = game.prompt
        if case .aim(let aim)? = prompt {
            for t in aim.targets {
                ctx.setStrokeColor(Self.ring.withAlphaComponent(t.inRange ? 1 : 0.35).cgColor)
                ctx.setLineWidth(2.5)
                ctx.strokeEllipse(in: cellRect(t.x, t.y, ox: ox, oy: oy).insetBy(dx: -2, dy: -2))
            }
            if let c = aim.current { outline(Self.gold, c.x, c.y) }
        } else if case .direction(let d)? = prompt {
            if !padShown {
                let p = player
                for dy in -1...1 {
                    for dx in -1...1 where !(dx == 0 && dy == 0 && !d.allowHere) { outline(Self.gold, p.x + dx, p.y + dy) }
                }
                d.neighbours.forEach { fill(Self.pathFill, $0.x, $0.y) }
            }
        } else {
            for k in stride(from: 1, to: game.pathPairs, by: 1) {
                let p = game.pathPoint(k)
                fill(Self.pathFill, p.x, p.y)
            }
            if let s = selected { outline(Self.gold, s.x, s.y) }
            /* The current target, between shots. */
            let tx = Int(meta[NB.META_TARGET_X]), ty = Int(meta[NB.META_TARGET_Y])
            if tx >= 0 { outline(Self.gold, tx, ty) }
        }
        if let f = flash { fill(Self.flashFill, f.x, f.y) }
        outline(Self.you, player.x, player.y, width: 1.5)
    }

    // MARK: gestures

    private func select(_ g: Point?) {
        selected = g
        if let g { Native.requestPreview(x: g.x, y: g.y) } else { Native.clearPreview() }
        setNeedsDisplay()
    }

    private func moreWaiting() -> Bool {
        !game.atPrompt && game.prompt == nil && game.topLine.contains("-more-")
    }

    @objc private func tapped(_ r: UITapGestureRecognizer) {
        guard r.state == .ended, let g = grid(at: r.location(in: self)) else { return }
        switch game.prompt {
        case .aim?:
            Trace.log("map.tap", ("kind", "aim"))
            Native.answer(Direction.aimAt(x: g.x, y: g.y))
            return
        case .direction(let d)?:
            Trace.log("map.tap", ("kind", "direction"))
            let dx = g.x - player.x, dy = g.y - player.y
            if abs(dx) <= 1 && abs(dy) <= 1 && (dx != 0 || dy != 0 || d.allowHere) { Native.answer(Direction.keypad(dx: dx, dy: dy)) }
            return
        default: break
        }
        /* In a fight -more- comes often and the eyes are on the map: any tap continues. */
        if moreWaiting() { Trace.log("more.tap", ("via", "map")); Native.pushKey(Keys.enter); return }
        guard game.atPrompt else { return }
        let p = player
        let adjacent = abs(g.x - p.x) <= 1 && abs(g.y - p.y) <= 1 && g != p
        let dist = max(abs(g.x - p.x), abs(g.y - p.y))
        if adjacent {
            Trace.log("map.tap", ("kind", "step"))
            select(nil)
            Native.requestTravel(x: g.x, y: g.y)
        } else if g == selected {
            Trace.log("map.tap", ("kind", "go"), ("dist", dist))
            selected = nil
            Native.requestTravel(x: g.x, y: g.y)
        } else {
            Trace.log("map.tap", ("kind", "preview"), ("dist", dist))
            select(g)
        }
    }

    /// Long press: describe what is there (monster recall, objects, terrain).
    @objc private func pressed(_ r: UILongPressGestureRecognizer) {
        guard r.state == .began, game.prompt == nil || !isAimingOrDirecting, let g = grid(at: r.location(in: self)) else { return }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        Trace.log("map.hold", ("kind", "inspect"))
        Native.requestInspect(x: g.x, y: g.y)
    }

    private var isAimingOrDirecting: Bool {
        switch game.prompt {
        case .aim?, .direction?: return true
        default: return false
        }
    }

    /// Pan mode drags the map with one finger, except while a swipe answers a prompt.
    @objc private func swiped(_ r: UIPanGestureRecognizer) {
        if swipeMode == .pan && !isAimingOrDirecting {
            if r.state == .began { Trace.log("map.swipe", ("kind", "pan")) }
            dragged(r)
            return
        }
        guard r.state == .ended else { return }
        let t = r.translation(in: self)
        guard hypot(t.x, t.y) >= 2 * cellW else { return }
        let dir = Direction.octantToDir[Direction.octant(dx: t.x, dy: t.y)]
        let run = swipeMode == .run
        Trace.log("map.swipe", ("kind", isAimingOrDirecting ? "answer" : (game.atPrompt && game.prompt == nil ? (run ? "run" : "step") : "none")))
        if isAimingOrDirecting {
            Native.answer(dir)
        } else if game.atPrompt && game.prompt == nil {
            Native.requestMove(dir, run: run)
        }
    }

    @objc private func pinched(_ r: UIPinchGestureRecognizer) {
        switch r.state {
        case .began: pinchStart = cellHeight
        case .changed:
            cellHeight = pinchStart * r.scale
            setNeedsDisplay()
        default: break
        }
    }

    @objc private func dragged(_ r: UIPanGestureRecognizer) {
        switch r.state {
        case .began: panFocus = r.location(in: self)
        case .changed:
            let f = r.location(in: self)
            followPlayer = false
            camX -= (f.x - panFocus.x) / cellW
            camY -= (f.y - panFocus.y) / cellH
            panFocus = f
            setNeedsDisplay()
        default: break
        }
    }
}
