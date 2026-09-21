#include "crypto.h"
#include "protocol.h"

#include <openssl/evp.h>
#include <openssl/rand.h>
#include <openssl/sha.h>

#include <cstring>

namespace crypto {

// Must match Crypto.java (client) so both sides derive the same key.
static constexpr const char* PSK = "remote-desktop-psk-2026";

static unsigned char g_key[32] = {0};
static bool g_ready = false;

void init() {
    if (g_ready) return;
    SHA256((const unsigned char*)PSK, std::strlen(PSK), g_key);
    g_ready = true;
}

std::vector<uint8_t> seal(const uint8_t* data, size_t len) {
    if (!g_ready) init();
    std::vector<uint8_t> out(Protocol::NONCE_SIZE + len + Protocol::TAG_SIZE);
    uint8_t* nonce = out.data();
    uint8_t* ctxt = out.data() + Protocol::NONCE_SIZE;
    uint8_t* tag = ctxt + len;

    if (RAND_bytes(nonce, Protocol::NONCE_SIZE) != 1) return {};

    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return {};
    int r = -1;
    int outl = 0;
    if (EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) == 1
        && EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN,
                               Protocol::NONCE_SIZE, nullptr) == 1
        && EVP_EncryptInit_ex(ctx, nullptr, nullptr, g_key, nonce) == 1
        && EVP_EncryptUpdate(ctx, ctxt, &outl, data, (int)len) == 1
        && EVP_EncryptFinal_ex(ctx, ctxt + outl, &outl) == 1
        && EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, Protocol::TAG_SIZE,
                               tag) == 1) {
        r = 0;
    }
    EVP_CIPHER_CTX_free(ctx);
    if (r != 0) return {};
    return out;
}

bool open(const uint8_t* in, size_t len, std::vector<uint8_t>& out) {
    if (!g_ready) init();
    if (len < Protocol::NONCE_SIZE + Protocol::TAG_SIZE) return false;
    size_t ctxt_len = len - Protocol::NONCE_SIZE - Protocol::TAG_SIZE;
    const uint8_t* nonce = in;
    const uint8_t* ctxt = in + Protocol::NONCE_SIZE;
    const uint8_t* tag = in + Protocol::NONCE_SIZE + ctxt_len;

    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return false;
    out.assign(ctxt_len, 0);
    int ok = 0;
    if (EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) == 1
        && EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN,
                               Protocol::NONCE_SIZE, nullptr) == 1
        && EVP_DecryptInit_ex(ctx, nullptr, nullptr, g_key, nonce) == 1) {
        int outl = 0;
        if (EVP_DecryptUpdate(ctx, out.data(), &outl, ctxt, (int)ctxt_len) == 1
            && EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, Protocol::TAG_SIZE,
                                   (void*)tag) == 1
            && EVP_DecryptFinal_ex(ctx, out.data() + outl, &outl) == 1) {
            ok = 1;
        }
    }
    EVP_CIPHER_CTX_free(ctx);
    if (!ok) {
        out.clear();
        return false;
    }
    return true;
}

} // namespace crypto