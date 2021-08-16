# RapidWright Java Coding Guidelines

## 1. Naming Scheme Definitions
| Naming Scheme  | Description  |
| ------------ | ------------ |
| **UpperCamelCase**  | Each word in a phrase is capitalized, white space, underscores, hyphens and dashes are removed  |
| **lowerCamelCase**  | Same as **UpperCamelCase**, except first word is not capitalized  |
| **ALL_CAPS**  | All letters are capitalized, whitespace, hypens and dashes are replaced with underscores  ( \_ ) |

Examples:

| Input Example  | UpperCamelCase  | lowerCamelCase   | ALL_CAPS  |
| ------------ | ------------ | ------------ | ------------ |
| Get Macro Primitives  | GetMacroPrimitives  | getMacroPrimitives  | GET_MACRO_PRIMITIVES  |
| Get Top EDIF Cell  | GetTopEDIFCell  | getTopEDIFCell  | GET_TOP_EDIF_CELL  |
| Disable Auto IO Buffers Name  | DisableAutoIOBuffersName  | disableAutoIOBuffersName  | DISABLE_AUTO_IO_BUFFERS_NAME  |

## 2. Java Construct Naming Conventions
| Java Construct  | Naming Convention   | Examples   | Counter Examples   |
| ------------ | ------------ | ------------ | ------------ |
| Class, Enum, & Interface Names  | Class, Enum and Interface names use **UpperCamelCase**. Any classes in the `com.xilinx.rapidwright.edif` package are prefixed with `EDIF`. Class names are typically nouns or noun phrases.  | `FileTools`, `EDIFNetlist`  | `file_tools`, `ENetlist`   |
| Package Names  | Package names are all **lowercase**, with consecutive words simply concatenated together (no underscores).  | `com.xilinx.rapidwright.design`  | `Com.Xilinx.RapidWright.Design`, `com.xilinx.rapidWright.design`  |
| Method Names  | Class names use **lowerCamelCase**. Method names are typically verbs or phrases.  | `getMacroPrimitives()`   | `get_MacroPrimitives()`, `do_it()`  |
| Parameter Names  | Parameter names use **lowerCamelCase**.  Names should be brief, descriptive and consistent.  | `device`, `portName`  | `d`, `d2`, `tmp`, `tmp_var`, `tmp_cnt`  |
| Variable Names  | Variable names use **lowerCamelCase**.  Names should be brief, descriptive and consistent. Single letter variables as a loop iterator are OK.  | `design`, `fileName`, `i`  | `Design`, `MyDesign`, `myDesign_small`  |
| Final Variable Names  | Final variable names use **ALL_CAPS**.   | `VERILOG_COMMENT`  | `verilog_comment`, `VerilogComment`  |
| Java Source Code Filenames  | The source file name uses the **UpperCamelCase** name of the top-level class it contains (of which there is exactly one), plus the .java extension.  | `Router.java`  | `router.java`, `Router_1.java`  |

## 3. Java Source Code Organization
A Java source file should consist of the following items, in order:
1. License and/or copyright information
2. Package statement
3. Import statements (no wild card imports and imports are ASCII sorted)
   1. All JDK library imports are organized first into their own section
   2. Followed by all other imports
4. Exactly one top-level class/enum/interface
   1. Inside classes/interfaces, class member variables should be declared first
   2. Next, any constructors should be declared, unless it is an enum, in which case the enumeration values come first
   3. The remaining order is left up to the developer, such as newer methods could be added at the bottom of the class

A blank line should separate each item from one another.

Below is a class that adheres to the organization described above:

```java
/*
 *
 * Copyright (c) 2018 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
 
package com.xilinx.rapidwright.design;
 
import java.util.ArrayList;
import java.util.List;
 
import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.edif.EDIFCell;
 
/**
 * Convenience object for capturing a logical cell pin name
 * with its respective cell.
 */
public class CellPin {
     
    private Cell cell;
     
    private String logicalPinName;
 
    public CellPin(Cell cell, String physicalPinName) {
        super();
        this.cell = cell;
        this.logicalPinName = physicalPinName;
    }
 
    public Cell getCell() {
        return cell;
    }
 
    public String getLogicalPinName() {
        return logicalPinName;
    }
}
```

## 4. Java Code Formatting
### 4.1 Whitespace 
1. Lines are terminated with a single newline character ('\n') and not the windows style ("\r\n") or other types.
2. Block indentation is currently 1 tab or 4 spaces.
3. Each statement should be separated by a line break.
4. Lines longer than ~100 characters should be broken into multiple lines.
5. A blank line should separate consecutive elements inside a class (variables, constructors, methods, nested classes, static blocks, etc)
6. Enum constants should be listed, one per line.
7. One variable declaration per line. declarations such as `int a, b;` are not used.

### 4.2 Braces
1. Braces are used with `if`, `else`, `for`, `do`, `while`, `try` and `catch` statements, even when the body is empty or contains only a single statement.
2. Code blocks brace style
   1. No line break before opening brace
   2. Line break after opening brace
   3. Line break before the closing brace
   4. Line break after the closing brace (unless followed by an 'else if' or equivalent)

A few examples are below: 

```java
if (a == b) {
    System.out.println("a == b");
} else if (a == c) {
    System.out.println("a == c");
} else {
    System.out.println("a != c && a != b");
}

for (int i=0; i < s.length(); i++) {
    System.out.println(s.charAt(i));
}

do {
    System.out.println("something");
} while (!queue.isEmpty());

try {
    FileInputStream in = new FileInputStream(fileName);
} catch (FileNotFoundException e) {
    System.err.println("ERROR: The file "+fileName+" could not be found.");
    return null;
}
```

### 4.3 Comments
1. Classes and all public methods and public variables should have a Javadoc comment unless their use is self-explanatory from the identifier name.
2. Javadocs are recommended for all other non-trivial methods and variables.
3. Comments inside methods should use double forward slash style comments `// ` followed by a space
4. Multi-line comments `/* ... */`, except in Javadoc and top file license inclusion, should be avoided.
5. For Javadocs describing classes and method, the multiline Javadoc pattern should be used:
   1. The first line of a Javadoc begins with `/**`, 
   2. each following line is prefixed by ` * ` followed by descriptive text or tags,
   3. The final line terminates with  ` */`
6. For Javadocs describing a variable, if the description can fit on one line, the single line pattern can be used, however if the description exceeds one line, the multiline pattern should be used.

Javadoc multiline pattern:
```
/**
 * Convenience object for capturing a logical cell pin name
 * with its respective cell.
 */
public class CellPin {
    ...
}
```
Javadoc single line pattern:
```
    ...
    /** Suffix of the device part files */
    public static final String DEVICE_FILE_SUFFIX = "_db.dat";
    ...
```



## 5. General Design Principles
### 5.1 DRY (Don't Repeat Yourself) / SSOT (Single Source of Truth)
1. "Every piece of knowledge must have a single, unambiguous, authoritative representation within a system." - Andy Hunt and Dave Thomas, [The Pragmatic Programmer](https://en.wikipedia.org/wiki/The_Pragmatic_Programmer "The Pragmatic Programmer")
1. Information, models, schemas are structured such that every data element is stored/edited in exactly one place.
   1. Similar programmatic behaviors should be parameterized in a method
   1. No copy/paste of code with minor tweaks
   1. Methods should be short and concise (generally 40-60 lines in length at most)

### 5.2 SOLID Design Principles
The RapidWright code base should strive to follow SOLID design principles (any existing violations should be re-written once identified)
1. [**S**ingle responsibility principle](https://en.wikipedia.org/wiki/Single_responsibility_principle "**S**ingle responsibility principle")
2. [**O**pen-closed principle](https://en.wikipedia.org/wiki/Open%E2%80%93closed_principle "**O**pen-closed principle")
3. [**L**iskov substitution principle](https://en.wikipedia.org/wiki/Liskov_substitution_principle "**L**iskov substitution principle")
4. [**I**nterface segregation principle](https://en.wikipedia.org/wiki/Interface_segregation_principle "**I**nterface segregation principle")
5. [**D**ependency inversion principle](https://en.wikipedia.org/wiki/Dependency_inversion_principle "**D**ependency inversion principle")
