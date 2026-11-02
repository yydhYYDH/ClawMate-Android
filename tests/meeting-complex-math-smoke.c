/* Numerical/ABI smoke check for the packaged library, on an Android device.
 * This is a test executable, not linked into the application. */
#include <complex.h>
#include <dlfcn.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

static int checked;
static void fail(const char *name) {
    const char *error = dlerror();
    fprintf(stderr, "FAIL %s: %s\n", name, error ? error : "numerical mismatch");
    exit(1);
}
static void *symbol(void *library, const char *name) {
    void *address = dlsym(library, name);
    if (!address) fail(name);
    return address;
}
static void near(const char *name, long double complex actual,
                 long double complex expected, long double tolerance) {
    if (!isfinite(creall(actual)) || !isfinite(cimagl(actual)) ||
        cabsl(actual - expected) > tolerance * (1 + cabsl(expected))) fail(name);
}
#define UNARY(name, reference) do { \
    long double complex (*function)(long double complex) = symbol(library, #name); \
    double complex points[] = { 0.25 + 0.5 * I, 2.0 - 0.25 * I }; \
    for (int i = 0; i < 2; ++i) near(#name, function(points[i]), reference(points[i]), 1e-10L); \
    ++checked; \
} while (0)

int main(int argc, char **argv) {
    if (argc != 2) { fprintf(stderr, "usage: smoke /absolute/path/to/library.so\n"); return 2; }
    void *library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!library) fail("dlopen");
    UNARY(cacoshl, cacosh); UNARY(cacosl, cacos);
    UNARY(casinhl, casinh); UNARY(casinl, casin);
    UNARY(catanhl, catanh); UNARY(catanl, catan);
    UNARY(ccoshl, ccosh); UNARY(ccosl, ccos);
    UNARY(cexpl, cexp); UNARY(clogl, clog);
    UNARY(csinhl, csinh); UNARY(csinl, csin);
    UNARY(ctanhl, ctanh); UNARY(ctanl, ctan);

    double complex (*log_d)(double complex) = symbol(library, "clog");
    float complex (*log_f)(float complex) = symbol(library, "clogf");
    near("clog", log_d(3 + 4 * I), log(5) + atan2(4, 3) * I, 1e-12L);
    near("clogf", log_f(3 + 4 * I), log(5) + atan2(4, 3) * I, 1e-5L);
    double complex (*pow_d)(double complex, double complex) = symbol(library, "cpow");
    float complex (*pow_f)(float complex, float complex) = symbol(library, "cpowf");
    long double complex (*pow_l)(long double complex, long double complex) = symbol(library, "cpowl");
    near("cpow", pow_d(1 + I, 2), 2 * I, 1e-12L);
    near("cpowf", pow_f(1 + I, 2), 2 * I, 1e-5L);
    near("cpowl", pow_l(1 + I, 2), 2 * I, 1e-12L);
    near("cpowl zero", pow_l(0, 2), 0, 1e-12L);
    checked += 5;

    void (*hyperbolic)(long double, long double *, long double *) = symbol(library, "_cchshl");
    long double c, s;
    hyperbolic(0.25L, &c, &s);
    near("_cchshl small", c + I * s, coshl(0.25L) + I * sinhl(0.25L), 1e-12L);
    hyperbolic(1, &c, &s);
    near("_cchshl large", c + I * s, coshl(1) + I * sinhl(1), 1e-12L);
    long double (*reduce_pi)(long double) = symbol(library, "_redupil");
    near("_redupil", reduce_pi(3.1415926535897932384626433832795028L + 0.25L), 0.25L, 1e-12L);
    long double (*tan_series)(long double complex) = symbol(library, "_ctansl");
    near("_ctansl", tan_series(0.1L + 0.2L * I), coshl(0.4L) - cosl(0.2L), 1e-12L);
    checked += 3;
    dlclose(library);
    printf("OK %d complex-math exports; numerical smoke passed\n", checked);
    return 0;
}
