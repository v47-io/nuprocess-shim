# NuProcess Shim

![GitHub Workflow Status][workflow-shield]
[![Maven Central][maven-shield]][maven-central]
![GitHub][license-shield]

[workflow-shield]: https://img.shields.io/github/actions/workflow/status/v47-io/nuprocess-shim/build.yml?branch=main
[maven-shield]: https://img.shields.io/maven-central/v/io.v47/nuprocess-shim
[maven-central]: https://central.sonatype.com/artifact/io.v47/nuprocess-shim
[license-shield]: https://img.shields.io/github/license/v47-io/nuprocess-shim

> A poor man's masquerade of the Java Process API pretending to be a low-overhead,
> non-blocking I/O, external Process execution implementation for Java. It is most
> certainly not a replacement for `java.lang.ProcessBuilder` and `java.lang.Process`,
> instead hiding them behind a more convenient API.

This library is based on Brett Wooldridge's [NuProcess][nuprocess] library and provides
the exact same API while not relying on any sophisticated mechanisms to start and
control external processes. It's intended to be used as a drop-in replacement for
NuProcess in environments where JNA cannot be used (e.g. GraalVM Native Image).

To keep a lid on the memory impact this library utilizes virtual threads which makes this
library incompatible with Java runtimes below version 21.

However, this implementation is not able to reduce the RAM required for the initial
start of external processes because `vfork()` cannot be utilized without crossing over
into JNI/JNA territory.

[nuprocess]: https://github.com/brettwooldridge/NuProcess

## Runtime Behavior

Processing the standard out of launched processes is slightly different from the original
library because Java doesn't eagerly read the standard out if it cannot fill its buffer or
hit EOF. The same applies to the standard error stream.

## Development

### Prerequisites

- JDK 21

### Copied code

I copied over the files describing the public interface and all applicable tests from the
original library. Surgical adjustments to the tests were made to reflect the changed runtime
behavior regarding standard out processing.

## Download

The library is available in the Central Repository.

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.v47:nuprocess-shim:2.0.4")
}
```

Make sure to exclude the original NuProcess artifact from your dependencies:
`com.zaxxer:nuprocess` (Sorry, Brett!)

## Usage

Just refer to Brett Wooldridge's [README][nuprocess-readme]

[nuprocess-readme]: https://github.com/brettwooldridge/NuProcess?tab=readme-ov-file#example

### JavaDocs

You can read Brett's original JavaDoc [here][javadoc] as it applies 100% to this library.

[javadoc]: http://brettwooldridge.github.io/NuProcess/apidocs/index.html

### Settings

The settings as described in Brett's readme have no effect when using this library.

### Limitations

Incidentally, because this library is just a wrapper around the Java Process APIs, no additional
limitations apply.

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

All Java code taken from `NuProcess` remains available under the Apache License Version 2 and 
the original creators retain their copyright.

All subsequent changes, entirely new code, and any artifacts based on this library are licensed
and distributed under the BSD 3-Clause Clear License.
