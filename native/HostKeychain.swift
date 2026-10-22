import Foundation
import Security
import LocalAuthentication

// A narrow stdin/stdout bridge. Credentials never appear in process arguments or files.
let arguments = CommandLine.arguments
func finish(_ code: Int32) -> Never { exit(code) }
guard arguments.count == 3, ["read", "write", "delete", "exists"].contains(arguments[1]),
      arguments[2].count == 64, arguments[2].allSatisfy({ $0.isHexDigit }) else { finish(2) }
let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
    kSecAttrService as String: "dev.runtimebay.ssh-password",
    kSecAttrAccount as String: arguments[2]]
let operation = arguments[1]
if operation == "write" {
    let input = FileHandle.standardInput.readData(ofLength: 16385)
    guard !input.isEmpty, input.count <= 16384 else { finish(2) }
    let update = SecItemUpdate(query as CFDictionary, [kSecValueData as String: input] as CFDictionary)
    if update == errSecItemNotFound {
        var item = query; item[kSecValueData as String] = input
        item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        finish(SecItemAdd(item as CFDictionary, nil) == errSecSuccess ? 0 : 3)
    }
    finish(update == errSecSuccess ? 0 : 3)
}
if operation == "delete" {
    let result = SecItemDelete(query as CFDictionary)
    finish(result == errSecSuccess || result == errSecItemNotFound ? 0 : 3)
}
var lookup = query
lookup[kSecMatchLimit as String] = kSecMatchLimitOne
if operation == "exists" {
    let context = LAContext(); context.interactionNotAllowed = true
    lookup[kSecUseAuthenticationContext as String] = context
}
if operation == "read" { lookup[kSecReturnData as String] = true }
else { lookup[kSecReturnAttributes as String] = true }
var result: CFTypeRef?
let status = SecItemCopyMatching(lookup as CFDictionary, &result)
if status == errSecItemNotFound { finish(4) }
guard status == errSecSuccess else { finish(3) }
if operation == "read" {
    guard let data = result as? Data, data.count <= 16384 else { finish(3) }
    FileHandle.standardOutput.write(data)
}
finish(0)
