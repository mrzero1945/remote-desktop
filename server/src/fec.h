#pragma once

// Reed-Solomon erasure code over GF(2^8) used for packet-level FEC.
//
// A video frame's data fragments are grouped into blocks of
// Protocol::FEC_BLOCK_DATA. For each block of K data fragments the server
// computes P extra parity fragments with a systematic generator matrix, so a
// client that receives any K of the (K + P) fragments can reconstruct the
// whole block. The client therefore tries FEC first and only falls back to a
// FRAGMENT_RETRANSMIT_REQ request for blocks that are not recoverable.
//
// The wire format is identical to the classic systematic Vandermonde
// construction (Luigi Rizzo). Tables must match between all endpoints, so
// keep this file in sync with the Java port (Fec.java).
//
// GF(2^8): primitive polynomial x^8 + x^4 + x^3 + x^2 + 1 (0x11D), generator
// alpha = 2.

#include <algorithm>
#include <cstdint>
#include <cstddef>
#include <cstring>
#include <vector>

namespace Fec {

constexpr uint32_t kPrimPoly = 0x11D;

class Gf {
public:
    Gf() {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            m_exp[i] = (uint8_t)x;
            m_log[x] = (uint8_t)i;
            x <<= 1;
            if (x & 0x100) x ^= static_cast<int>(kPrimPoly);
        }
        for (int i = 255; i < 512; i++) m_exp[i] = m_exp[i - 255];
    }

    uint8_t mul(uint8_t a, uint8_t b) const {
        if (a == 0 || b == 0) return 0;
        return m_exp[m_log[a] + m_log[b]];
    }

    uint8_t inv(uint8_t a) const {
        if (a == 0) return 0;
        return m_exp[255 - m_log[a]];
    }

    uint8_t pow_alpha(int e) const {
        return m_exp[(e % 255 + 255) % 255];
    }

private:
    uint8_t m_exp[512];
    uint8_t m_log[256];
};

inline const Gf& gf() {
    static Gf g;
    return g;
}

/**
 * Systematic (K+P) x K generator matrix, packed row-major.
 *
 * The code is the classic Vandermonde RS: row i evaluates the message at
 * the field point alpha^i, i.e. v_i[c] = alpha^(i*c). Taking the top K rows
 * as the message basis M and the bottom P rows as V, the codeword rows of
 * the systematic code are
 *
 *   data rows : e_c            (identity, message symbols pass through)
 *   parity row j : v_{K+j} * M^-1
 *
 * so "parity = parity_row * data" reproduces the same code as the plain
 * Vandermonde evaluation. Because the (K+P) x K Vandermonde matrix has the
 * MDS property (any K rows are independent), any K symbols of a block are
 * enough to recover all K data fragments.
 */
inline std::vector<uint8_t> build_generator(int k, int p) {
    const Gf& f = gf();
    std::vector<uint8_t> g((size_t)(k + p) * k);

    // Vandermonde rows v_i = [alpha^(i*0), alpha^(i*1), ..., alpha^(i*(K-1))].
    std::vector<uint8_t> v((size_t)(k + p) * k);
    for (int i = 0; i < k + p; i++) {
        uint8_t alpha_i = f.pow_alpha(i);
        uint8_t s = 1;
        for (int c = 0; c < k; c++) {
            v[(size_t)i * k + c] = s;
            s = f.mul(s, alpha_i);
        }
    }

    // Invert the top KxK block M: Gauss-Jordan on the augmented [M | I]:
    // the left half becomes I and the right half M^-1.
    std::vector<uint8_t> aug((size_t)k * 2 * k, 0);
    for (int i = 0; i < k; i++) {
        memcpy(&aug[(size_t)i * 2 * k], &v[(size_t)i * k], (size_t)k);
        aug[(size_t)i * 2 * k + k + i] = 1;
    }
    for (int i = 0; i < k; i++) {
        if (aug[(size_t)i * 2 * k + i] == 0) {
            int t = i + 1;
            while (t < k && aug[(size_t)t * 2 * k + i] == 0) t++;
            if (t == k) continue; // degenerate K=0 case never happens
            for (int c = 0; c < 2 * k; c++) {
                std::swap(aug[(size_t)i * 2 * k + c], aug[(size_t)t * 2 * k + c]);
            }
        }
        uint8_t piv_inv = f.inv(aug[(size_t)i * 2 * k + i]);
        for (int c = 0; c < 2 * k; c++) {
            aug[(size_t)i * 2 * k + c] = f.mul(aug[(size_t)i * 2 * k + c], piv_inv);
        }
        for (int j = 0; j < k; j++) {
            if (j == i) continue;
            uint8_t factor = aug[(size_t)j * 2 * k + i];
            if (!factor) continue;
            for (int c = 0; c < 2 * k; c++) {
                aug[(size_t)j * 2 * k + c] ^= f.mul(factor, aug[(size_t)i * 2 * k + c]);
            }
        }
    }

    // Data rows are the identity.
    for (int i = 0; i < k; i++) {
        for (int c = 0; c < k; c++) g[(size_t)i * k + c] = (i == c) ? 1 : 0;
    }

    // Parity rows: v_{k+j} * M^-1.
    for (int j = 0; j < p; j++) {
        uint8_t* row = &g[(size_t)(k + j) * k];
        for (int i = 0; i < k; i++) {
            uint8_t acc = 0;
            for (int c = 0; c < k; c++) {
                uint8_t m = aug[(size_t)c * 2 * k + k + i]; // M^-1[c][i]
                uint8_t vv = v[(size_t)(k + j) * k + c];
                acc ^= f.mul(vv, m);
            }
            row[i] = acc;
        }
    }
    return g;
}

/**
 * Encode one FEC block.
 * data[i]   : the K data fragments (each fragment's true byte length may be
 *             less than padded_len; runts are zero filled).
 * data_len  : per-fragment true lengths.
 * padded_len: L, the block length used for all vectors.
 * parity    : becomes P rows of padded_len bytes each.
 */
inline void encode_block(int k, int p,
                         const uint8_t* const* data,
                         const int* data_len,
                         size_t padded_len,
                         std::vector<std::vector<uint8_t>>& parity) {
    parity.assign((size_t)p, std::vector<uint8_t>(padded_len, 0));
    if (k <= 0) return;

    const Gf& f = gf();
    std::vector<uint8_t> g = build_generator(k, p);
    for (int j = 0; j < p; j++) {
        const uint8_t* row = &g[(size_t)(k + j) * k];
        std::vector<uint8_t>& out = parity[(size_t)j];
        for (int c = 0; c < k; c++) {
            uint8_t coef = row[c];
            if (!coef) continue;
            const uint8_t* d = data[c];
            int dl = data_len ? data_len[c] : (int)padded_len;
            for (size_t t = 0; t < padded_len; t++) {
                uint8_t v = (t < (size_t)dl) ? d[t] : 0;
                if (v) out[t] ^= f.mul(coef, v);
            }
        }
    }
}

/**
 * Recover the K data fragments of a block from the received symbols.
 * rows[i] : generator row index of received symbol i. Data fragment index c
 *           maps to row c; parity fragment index j maps to row k + j.
 * recv[i] : the padded received symbol (padded_len bytes).
 * At least K symbols must be given; any K of them are sufficient (MDS code),
 * so the first K rows are used for the solve.
 * recovered: becomes the K recovered data fragments (padded_len bytes each).
 */
inline bool recover_block(int k, int p,
                          const std::vector<int>& rows,
                          const std::vector<std::vector<uint8_t>>& recv,
                          size_t padded_len,
                          std::vector<std::vector<uint8_t>>& recovered) {
    if ((int)rows.size() < k || (int)recv.size() < k) return false;

    const Gf& f = gf();
    std::vector<uint8_t> g = build_generator(k, p);

    // A: rows of the received symbols, columns = data fragment position.
    std::vector<uint8_t> a((size_t)k * k);
    for (int r = 0; r < k; r++) {
        if (rows[r] < 0 || rows[r] >= k + p) return false;
        memcpy(&a[(size_t)r * k], &g[(size_t)rows[r] * k], (size_t)k);
    }

    // Invert A in place (Gauss-Jordan, identity augmented separately).
    std::vector<uint8_t> inv((size_t)k * k, 0);
    for (int i = 0; i < k; i++) inv[(size_t)i * k + i] = 1;

    for (int i = 0; i < k; i++) {
        if (a[(size_t)i * k + i] == 0) {
            int t = i + 1;
            while (t < k && a[(size_t)t * k + i] == 0) t++;
            if (t == k) return false;
            for (int c = 0; c < k; c++) {
                std::swap(a[(size_t)i * k + c], a[(size_t)t * k + c]);
                std::swap(inv[(size_t)i * k + c], inv[(size_t)t * k + c]);
            }
        }
        uint8_t piv_inv = f.inv(a[(size_t)i * k + i]);
        for (int c = 0; c < k; c++) {
            a[(size_t)i * k + c] = f.mul(a[(size_t)i * k + c], piv_inv);
            inv[(size_t)i * k + c] = f.mul(inv[(size_t)i * k + c], piv_inv);
        }
        for (int j = 0; j < k; j++) {
            if (j == i) continue;
            uint8_t factor = a[(size_t)j * k + i];
            if (!factor) continue;
            for (int c = 0; c < k; c++) {
                a[(size_t)j * k + c] ^= f.mul(factor, a[(size_t)i * k + c]);
                inv[(size_t)j * k + c] ^= f.mul(factor, inv[(size_t)i * k + c]);
            }
        }
    }

    // recovered[c] = sum over received symbols r of inv[c][r] * recv[r]
    recovered.assign((size_t)k, std::vector<uint8_t>(padded_len, 0));
    for (int c = 0; c < k; c++) {
        const uint8_t* invrow = &inv[(size_t)c * k];
        for (int r = 0; r < k; r++) {
            uint8_t coef = invrow[r];
            if (!coef) continue;
            const uint8_t* sym = recv[(size_t)r].data();
            for (size_t t = 0; t < padded_len; t++) {
                if (sym[t]) {
                    recovered[(size_t)c][t] ^= f.mul(coef, sym[t]);
                }
            }
        }
    }
    return true;
}

} // namespace Fec