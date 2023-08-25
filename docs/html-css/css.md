
# Cascading Style Sheets

## Important Topics

- Inline, Internal and External CSS

- CSS Selectors
    - Simple selectors `select elements based on name, id, class`
    - Combinator selectors `select elements based on a specific relationship between them`
        - descendant selector `space`
        - child selector `>`
        - adjacent sibling selector `+`
        - general sibling selector `~`
    - Pseudo-class selectors `select elements based on a certain state`
    - Pseudo-elements selectors `select and style a part of an element`
    - Attribute selectors `select elements based on an attribute or attribute value`

- CSS Pseudo-classes
    - :link
    - :visited
    - :hover
    - :active
    - :focus
    - :first-child
    - :last-child
    - :last-of-type
    - :nth-child(n)
    - :nth-last-child(n)
    - :nth-last-of-type(n)
    - :nth-of-type(n)
    - :only-of-type

- CSS Pseudo-elements
    - ::before
    - ::after
    - ::first-letter
    - ::first-line
    - ::marker
    - ::selection


- Conflicts Between Selectors
    - There are three reasons for CSS style conflict:
        - Specificity
        - Inheritance
        - !important
    - Specificity Hierarchy
        - Inline styles - Example: `<h1 style="color: pink;">`
        - IDs - Example: `#navbar`
        - Classes, pseudo-classes, attribute selectors - Example: `.test, :hover, [href]`
        - Elements and pseudo-elements - Example: `h1, ::before`
- CSS Conflict Resolution Rules

- Inheritance and the Universal Selector

- CSS Box Model
    - Content - `The content of the box, where text and images appear`
    - Padding - `Clears an area around the content. The padding is transparent`
    - Border - `A border that goes around the padding and content`
    - Margin - `Clears an area outside the border. The margin is transparent`

- Types of Box Models in CSS
    - HTML Block and Inline Elements
    - block
    - inline
    - inline-block

- CSS box-sizing property
    - content-box - `Default`
    - border-box
    - initial - `Sets this property to its default value`
    - inherit - `Inherits this property from its parent element`

- CSS Layout - The position Property
    - static
    - relative
    - absolute
    - fixed
    - sticky

- CSS Layout - Floats

- CSS Layout - Flexbox
    - CSS Flex Model
        - Flex Container
        - Flex Items
        - Main axis 
        - Cross axis 
    - CSS Flex Container Properties
        - display as flex
        - flex-flow - `flex-flow property is a shorthand property for setting both the flex-direction and flex-wrap properties`
            - flex-direction
            - flex-wrap
        - justify-content - `align the grid content on main-axis (horizontally)`
        - align-content - `align the grid content on cross-axis (vertically)`
        - justify-items
        - align-items
        - gap
    - CSS Flex Items Properties
        - flex - `flex property is a shorthand property for the flex-grow, flex-shrink, and flex-basis properties`
            - flex-grow
            - flex-shrink
            - flex-basis
        - justify-self
        - align-self
        - order

- CSS Layout - Grid
    - CSS Grid Model
        - Grid Container
        - Grid Items
        - Grid Axis
            - Block or Column Aaxis
            - Inline or Row Axis
        - Grid Lines
        - Grid Cell - `might be filled by a grid item or it can be empty`
        - Grid Tracks
            - Column Track
            - Row Track
        - Gutters (gaps)

    - CSS Grid Container Properties
        - grid-template-columns
        - grid-template-rows
        - grid-template-areas
        - justify-content
        - align-content
        - justify-items
        - align-items
        - gap 
            - row-gap
            - column-gap

    - CSS Grid Items Properties
        - grid-column - `grid-column property is a shorthand property for the grid-column-start and grid-column-end properties`
            - grid-column-start
            - grid-column-end
        - grid-row - `grid-row property is a shorthand property for the grid-row-start and grid-row-end properties`
            - grid-row-start
            - grid-row-end
        - grid-area
        - justify-self
        - align-self