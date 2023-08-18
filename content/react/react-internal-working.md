---
title: "React Render Internal Working"
weight: 2
---

https://www.youtube.com/playlist?list=PLC3y8-rFHvwg7czgqpQIBEAHn8D6l530t

Any screen update in a React app happens in three steps:

- Trigger
- Render
- Commit

### Step 1: Trigger a render

There are two reasons for a component to render:

- It’s the component’s initial render.
- The component’s (or one of its ancestors’) state has been updated.

### Step 2: React renders your components

After you trigger a render, React calls your components to figure out what to display on screen. “Rendering” is React calling your components.

- On initial render, React will call the root component.
- For subsequent renders, React will call the function component whose state update triggered the render.

### Step 3: React commits changes to the DOM

After rendering (calling) your components, React will modify the DOM.

- For the initial render, React will use the appendChild() DOM API to put all the DOM nodes it has created on screen.
- For re-renders, React will apply the minimal necessary operations (calculated while rendering!) to make the DOM match the latest rendering output.

{{% notice note %}}
You can use Strict Mode to find mistakes in your components.\
React does not touch the DOM if the rendering result is the same as last time.
{{% /notice %}}
