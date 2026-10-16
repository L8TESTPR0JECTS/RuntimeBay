import AppKit

// Render the README's branded SVG into the raster sizes required by macOS icons.
guard CommandLine.arguments.count == 3 else { fatalError("Usage: render-icon.swift input.svg output.png") }
let source = CommandLine.arguments[1]
let destination = CommandLine.arguments[2]
guard let artwork = NSImage(contentsOfFile: source),
      let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 1024, pixelsHigh: 1024,
          bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
          colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0),
      let context = NSGraphicsContext(bitmapImageRep: bitmap) else { fatalError("Could not load or render the branded SVG") }
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = context
artwork.draw(in: NSRect(x: 0, y: 0, width: 1024, height: 1024), from: .zero, operation: .copy, fraction: 1)
NSGraphicsContext.restoreGraphicsState()
guard let png = bitmap.representation(using: .png, properties: [:]) else { fatalError("Could not encode app icon") }
try png.write(to: URL(fileURLWithPath: destination), options: .atomic)
