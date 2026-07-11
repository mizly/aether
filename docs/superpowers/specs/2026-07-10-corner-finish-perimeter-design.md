# Corner Finish Perimeter Fix

## Scope

Leave both existing lane sweeps unchanged. Fix only the perimeter-breaking stage that runs afterward.

## Required sequence

1. Complete the existing first four-row corner clear.
2. Face south and break exactly three upper rows, then one bottom row.
3. Release the ruler and movement inputs.
4. Move forward one block, then release movement.
5. Rotate west and verify the rotation completed before breaking again.
6. Clear the west and final north perimeter walls with the same three-upper, one-bottom limit.

## Implementation

Replace the continuously held perimeter use input with discrete, removal-confirmed activations. Each activation must finish and release the use key before another activation or movement/rotation transition begins. Keep the existing corner entry, pathfinding, plot bounds, and lane-sweep behavior.

If an expected removal, movement, or rotation does not complete before its existing timeout, release held inputs and stop that perimeter leg instead of continuing in the wrong direction.

## Verification

Add one focused regression check for the ordered perimeter actions: three upper removals, one bottom removal, input release, one-block movement, west rotation, and only then the next break. Run the focused check and the normal Gradle build.
