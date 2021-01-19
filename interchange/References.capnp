@0xa0082c6893796a86;
using Java = import "/capnp/java.capnp";
$Java.package("com.xilinx.rapidwright.interchange");
$Java.outerClassname("References");

enum ReferenceType {
    root       @0;
    rootValue  @1;
    parent     @2;
}

enum ImplementationType {
    enumerator @0;
}
