---
description: Reach a value several obfuscated classes deep, and reuse the path in any patch.
---

# Reading obfuscated objects

Sometimes your patch has an object from the app, and needs a value buried inside it. Say a feed post's video link sits at `post.attributes.videos[0].getUrl()`. In the real app, every class and field on that path has a meaningless name like `a.b.c`, and the names change with every release.

You could find each step with its own target, then read them one by one with null checks. A *binding* does that for you. It describes the whole path by structure, once, and any patch can then read it in a code block, with every step checked for null.

## Step 1: where the path starts

A binding starts from one class: the type of the object you'll pass in. Name it with one of three sources:

- `fromClass(target)`, when you have a target for the class.
- `fromField { }`, when you know where the app keeps the object, but not its class.
- `fromMethod(target)`, when the path follows what a method does, step by step.

Here the click handler reads the post from a field, just before it loads the string `"post_clicked"`:

```kotlin
val post = bind("post") {
    fromField("feedPostField") {
        owner(onPostClicked.owner)
        nearestObjectReadBeforeString("post_clicked")
    }
}
```

The locator looks through the methods of `owner`, finds the object field read closest before that string, and takes its type as the binding's starting class. `post.sourceField` is that field, which you'll read in step 3.

## Step 2: describe the path

Each `member` is a named path from the starting object. Every step works on what the step before it produced:

```kotlin
private const val VIDEO = "com.example.app.model.Video"

val post = bind("post") {
    fromField("feedPostField") {
        owner(onPostClicked.owner)
        nearestObjectReadBeforeString("post_clicked")
    }
    member("attributes") {
        field("attributes") {
            rankBy("type with list getters") { zeroArgListGetters() }
        }
    }
    member("videoUrl") {
        member("attributes")
        listGetter("videos") {
            rankBy("callers cast to Video") { callSitesFollowedByCast(VIDEO) }
        }
        first()
        cast(VIDEO)
        callInterface(VIDEO, "getUrl", "()Ljava/lang/String;")
    }
}
```

Reading `videoUrl` from the top:

1. `member("attributes")` starts from the path declared above it. A path can begin with any member declared earlier in the same binding.
2. `listGetter("videos") { }` calls the attributes class's method that takes nothing and returns a `List`. That class may have several, so the ranking picks the one whose callers cast its elements to `Video`. `"videos"` is only a label.
3. `first()` takes the list's first element.
4. `cast(VIDEO)` casts it to the video type, which this app doesn't obfuscate.
5. `callInterface(...)` calls `getUrl()` on it.

The `attributes` member finds its field the same way: among the post class's instance fields, it ranks each field's type by how many list getters that type has.

[`PathQuery`](reference_targets.md#pathquery) lists every step, including reading a field by its type, picking an `Object` field by what it holds, and following the fields a method reads.

## Step 3: read it in a code block

A binding finds the class, not where the app keeps the object. Your patch reads the object first, then passes it in:

```kotlin
onPostClicked.before {
    val url = post.member("videoUrl", thisObject.field(post.sourceField))
    whenNotNull(url) { Downloads.offer(url) }
}
```

`post.member(name, value)` adds code that walks the path from `value`. If any object on the way is null, or the list is empty, the result is null, or zero for a number. So check it with `whenNotNull` before you use it.

## Reusing a binding

Declare a binding once, as a top-level value, and use it in any patch. Like other targets, it is searched for the first time a patch uses it.

Two more tools help when bindings build on each other:

- `raw { }` is a path from the value you pass in to the bound object itself, for when you pass in something that leads to it. `post.of(value)` walks it.
- `bind(name, otherBinding) { }` declares a member whose result is another binding's object. When its path doesn't settle the result's type, it takes that binding's type.

## When a binding fails

Each step is checked while patching, not in the app. A step that finds nothing fails the patch with a message naming the step and the class, for example `No instance field matching Lcom/example/a/c; on Lcom/example/a/b;`, or an ambiguous field when a locator without ranking finds several. Add a `rankBy`, or a more specific step.

Next: [Raw bytecode](13_bytecode.md).
