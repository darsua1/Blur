# Third-party notices

Blur incorporates or builds against the components below. Each remains governed by
its own licence; nothing in the Blur licence restricts your rights under them.

---

## Neutralino.js — MIT

The Blur desktop app (`Blur.exe`) is built on Neutralinojs, and the shipped
executable **contains** the Neutralino runtime binary. Its MIT licence requires this
notice to travel with all copies.

```
MIT License

Copyright (c) 2021 Neutralinojs and contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Project: https://github.com/neutralinojs/neutralinojs

---

## Fabric Loader, Fabric API, Fabric Loom — Apache License 2.0

The Blur mod is built against and runs on the Fabric toolchain. These are **not
bundled** into the Blur jar — users install Fabric Loader and Fabric API themselves,
and Loom is a build-time-only dependency.

Copyright © FabricMC. Licensed under the Apache License, Version 2.0:
http://www.apache.org/licenses/LICENSE-2.0

Project: https://fabricmc.net

---

## Mixin — MIT

Used via Fabric Loader for the one client-side hook Blur needs. Not bundled.

Copyright © SpongePowered. https://github.com/SpongePowered/Mixin

---

## Gson — Apache License 2.0

Used for JSON serialisation. Provided by Minecraft's own runtime; not bundled.

Copyright © Google Inc. http://www.apache.org/licenses/LICENSE-2.0

---

## Minecraft

Blur is not affiliated with, endorsed by, or associated with Mojang Studios or
Microsoft. Minecraft is a trademark of Mojang Studios. Blur ships no Minecraft code
or assets.
