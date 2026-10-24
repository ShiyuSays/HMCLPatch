/*
 * HMCLNoIPCheck - compile-time stub.
 *
 * `GEOCLASS_NATION` is a `static final int`, therefore javac inlines its value and the interface is
 * never referenced at runtime. HMCL's shadowJar minimizer drops WinConstants from the shipped jar,
 * so this stub provides the constant needed to compile the patched RegionUtils.
 */
package org.jackhuang.hmcl.util.platform.windows;

public interface WinConstants {

    int GEOCLASS_NATION = 16;
}
