package com.remotedesktop.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Reed-Solomon erasure code over GF(2^8) used for packet-level FEC.
 *
 * Mirror of server/src/fec.h (must stay in sync). Primitive polynomial
 * x^8+x^4+x^3+x^2+1 (0x11D), generator alpha = 2.
 */
public final class Fec {
    private static final int PRIM_POLY = 0x11D;

    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= PRIM_POLY;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }

    private Fec() {}

    private static int mul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[LOG[a] + LOG[b]];
    }

    private static int inv(int a) {
        if (a == 0) return 0;
        return EXP[255 - LOG[a]];
    }

    private static int powAlpha(int e) {
        e %= 255;
        if (e < 0) e += 255;
        return EXP[e];
    }

    /**
     * Systematic (K+P) x K generator matrix, packed row-major
     * (parity rows at rows K..K+P-1; data rows are the identity).
     */
    private static int[] buildGenerator(int k, int p) {
        int[] g = new int[(k + p) * k];

        // Vandermonde rows v_i[c] = alpha^(i*c).
        int[] v = new int[(k + p) * k];
        for (int i = 0; i < k + p; i++) {
            int alphaI = powAlpha(i);
            int s = 1;
            for (int c = 0; c < k; c++) {
                v[i * k + c] = s;
                s = mul(s, alphaI);
            }
        }

        // Invert top KxK block M: Gauss-Jordan on [M | I].
        int[] aug = new int[k * 2 * k];
        for (int i = 0; i < k; i++) {
            System.arraycopy(v, i * k, aug, i * 2 * k, k);
            aug[i * 2 * k + k + i] = 1;
        }
        for (int i = 0; i < k; i++) {
            if (aug[i * 2 * k + i] == 0) {
                int t = i + 1;
                while (t < k && aug[t * 2 * k + i] == 0) t++;
                if (t == k) continue;
                for (int c = 0; c < 2 * k; c++) {
                    int tmp = aug[i * 2 * k + c];
                    aug[i * 2 * k + c] = aug[t * 2 * k + c];
                    aug[t * 2 * k + c] = tmp;
                }
            }
            int pivInv = inv(aug[i * 2 * k + i]);
            for (int c = 0; c < 2 * k; c++) {
                aug[i * 2 * k + c] = mul(aug[i * 2 * k + c], pivInv);
            }
            for (int j = 0; j < k; j++) {
                if (j == i) continue;
                int factor = aug[j * 2 * k + i];
                if (factor == 0) continue;
                for (int c = 0; c < 2 * k; c++) {
                    aug[j * 2 * k + c] ^= mul(factor, aug[i * 2 * k + c]);
                }
            }
        }

        for (int i = 0; i < k; i++) {
            for (int c = 0; c < k; c++) g[i * k + c] = (i == c) ? 1 : 0;
        }
        for (int j = 0; j < p; j++) {
            int rowBase = (k + j) * k;
            for (int i = 0; i < k; i++) {
                int acc = 0;
                for (int c = 0; c < k; c++) {
                    int mInv = aug[c * 2 * k + k + i]; // M^-1[c][i]
                    int vv = v[(k + j) * k + c];
                    acc ^= mul(vv, mInv);
                }
                g[rowBase + i] = acc;
            }
        }
        return g;
    }

    /**
     * Recover the K data fragments of a block from received symbols.
     * rows[i]: generator row index of received symbol i (data fragment c -> c,
     *          parity fragment j -> K+j). recv[i]: padded symbol.
     * At least K symbols must be given; the first K rows are used.
     * Returns the K recovered fragments (paddedLen bytes each) or null.
     */
    public static List<byte[]> recoverBlock(int k, int p, int[] rows,
                                            List<byte[]> recv, int paddedLen) {
        if (rows.length < k || recv.size() < k) return null;

        int[] g = buildGenerator(k, p);

        int[] a = new int[k * k];
        for (int r = 0; r < k; r++) {
            int row = rows[r];
            if (row < 0 || row >= k + p) return null;
            System.arraycopy(g, row * k, a, r * k, k);
        }

        int[] invM = new int[k * k];
        for (int i = 0; i < k; i++) invM[i * k + i] = 1;

        for (int i = 0; i < k; i++) {
            if (a[i * k + i] == 0) {
                int t = i + 1;
                while (t < k && a[t * k + i] == 0) t++;
                if (t == k) return null;
                for (int c = 0; c < k; c++) {
                    int tmp = a[i * k + c];
                    a[i * k + c] = a[t * k + c];
                    a[t * k + c] = tmp;
                    tmp = invM[i * k + c];
                    invM[i * k + c] = invM[t * k + c];
                    invM[t * k + c] = tmp;
                }
            }
            int pivInv = inv(a[i * k + i]);
            for (int c = 0; c < k; c++) {
                a[i * k + c] = mul(a[i * k + c], pivInv);
                invM[i * k + c] = mul(invM[i * k + c], pivInv);
            }
            for (int j = 0; j < k; j++) {
                if (j == i) continue;
                int factor = a[j * k + i];
                if (factor == 0) continue;
                for (int c = 0; c < k; c++) {
                    a[j * k + c] ^= mul(factor, a[i * k + c]);
                    invM[j * k + c] ^= mul(factor, invM[i * k + c]);
                }
            }
        }

        List<byte[]> recovered = new ArrayList<>(k);
        for (int c = 0; c < k; c++) {
            byte[] out = new byte[paddedLen];
            for (int r = 0; r < k; r++) {
                int coef = invM[c * k + r];
                if (coef == 0) continue;
                byte[] sym = recv.get(r);
                for (int t = 0; t < paddedLen; t++) {
                    int s = sym[t] & 0xFF;
                    if (s != 0) out[t] ^= (byte) mul(coef, s);
                }
            }
            recovered.add(out);
        }
        return recovered;
    }
}