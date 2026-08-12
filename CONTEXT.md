# CarCues

CarCues provides visual references intended to reduce passenger motion sickness while a mobile device is in use inside a moving vehicle.

## Language

**Motion cue**:
A peripheral dot whose displacement visually represents estimated vehicle acceleration.
_Avoid_: Circle, grid dot

**Cue field**:
The complete set of motion cues displayed across both side cue rails.
_Avoid_: Circle grid, overlay grid

**Cue rail**:
A vertical group of motion cues positioned along the left or right screen edge.
_Avoid_: Edge dots, grid column

**Cue session**:
The period during which the cue field is enabled system-wide, from explicit activation until explicit deactivation.
_Avoid_: Service session, overlay session

**Vehicle acceleration estimate**:
The app's estimate of the vehicle motion relevant to cue displacement, distinguished from gravity, device handling, and sensor noise.
_Avoid_: Raw acceleration, phone movement

**Passenger road validation**:
A safety-bounded evaluation performed by a vehicle passenger to confirm that motion cues behave usefully during real travel.
_Avoid_: Driving test, road test
