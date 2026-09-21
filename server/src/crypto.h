#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>

// AES-256-GCM transport encryption with a baked-in pre-shared key (PSK).
// Every fragment is sealed with a fresh random nonce, so identical plaintext
// fragments produce different ciphertexts. Layout on the wire:
//   [12-byte nonce][ciphertext][16-byte tag]
// The key is SHA-256(PSK) derived once at startup in crypto.cpp.
namespace crypto {

// Derive the AES-256 key from the shared PSK. Call once before any seal/open.
void init();

// Seal a payload into nonce||ciphertext||tag. Empty vector on failure.
std::vector<uint8_t> seal(const uint8_t* data, size_t len);

// Open nonce||ciphertext||tag. Returns false on auth failure / bad length.
bool open(const uint8_t* in, size_t len, std::vector<uint8_t>& out);

} // namespace crypto