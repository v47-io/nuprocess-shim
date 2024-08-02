# NuProcess Shim

> A poor man's masquerade of the Java Process API pretending to be a low-overhead,
non-blocking I/O, external Process execution implementation for Java. It is most
certainly not a replacement for `java.lang.ProcessBuilder` and `java.lang.Process`,
instead hiding them behind a more convenient API.

This library is based on Brett Wooldridge's [NuProcess][nuprocess] library and provides
the exact same API while not relying on any sophisticated mechanisms to start and
control external processes. It's intended to be used as a drop-in replacement for 
NuProcess in environments where JNA cannot be used (e.g. GraalVM Native Image).

It still intends to keep a lid on memory usage (just like NuProcess) by using a limited
number of threads to handle process I/O, regardless of the number of processes started.

However, this implementation is not able to reduce the RAM required for the initial
start of external processes because `vfork()` cannot be utilized without crossing over
into JNI/JNA territory.

[nuprocess]: https://github.com/brettwooldridge/NuProcess

## Prerequisites

- JDK 21

## Download

The library is available in the Central Repository.

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.v47:nuprocess-shim:0.0.0-SNAPSHOT")
}
```

Make sure to exclude the original NuProcess artifact from your dependencies: 
`com.zaxxer:nuprocess` (Sorry, Brett!)

## Usage

Just refer to Brett Wooldridge's [README][nuprocess-readme]

[nuprocess-readme]: https://github.com/brettwooldridge/NuProcess?tab=readme-ov-file#example

## Contributing

If you want to contribute, I recommend you head on over to the `NuProcess` repository and 
contribute there. This library only exists as a stop-gap measure until JNA becomes available
for GraalVM Native Image, after which it will be discontinued.

But if you still want to contribute: Knock yourself out and create a pull request!

### Licensing of contributed material

Before creating a pull request make sure you have all the rights to the contributed material
or have permission from the original creators to publish it in this fashion.

Furthermore, by creating a pull request you agree to provide the contributed material under the
terms of the BSD 3-Clause Clear License.

## License

All Java code taken from `NuProcess` remains available under the Apache License Version 2.

All subsequent changes, entirely new code, and any artifacts based on this library are licensed
and distributed under the BSD 3-Clause Clear License.
