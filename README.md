# Herzborg Binding

This binding supports smart curtain motors by Herzborg (http://www.herzborg.de/product.aspx#motor)

## Supported Things

- RS485 Serial bus
- Curtain motor.

The binding was developed and tested using DT300TV-1.2/14 type motor; others are expected to be compatible

## Discovery

Due to nature of serial bus being used, no automatic discovery is possible.

## Thing Configuration

### Serial Bus Bridge (id "serial_bus")

| Parameter | Meaning                                                 |
|-----------|---------------------------------------------------------|
| port      | Serial port name to use                                 |

Herzborg devices appear to use fixed 9600 8n1 communication parameters, so no other parameters are needed

### Curtain Motor Thing (id "curtain")

| Parameter     | Meaning                                                 |
|---------------|---------------------------------------------------------|
| address       | Address of the motor on the serial bus.                 |
| poll_interval | Polling interval in seconds                             |

## Channels

| channel     | type          | description                                   |
|-------------|---------------|-----------------------------------------------|
| position    | RollerShutter | Controls position of the curtain. Position reported back is in percents; 0 - fully closed; 100 - fully open |

heraborg.things:

```
Bridge herzborg:serial_bus:my_herzborg_bus [ port="/dev/ttyAMA1" ]
{
    Thing herzborg:curtain:livingroom [ address=1234, poll_interval=1 ]
}
```

herzborg.items:

```
Rollershutter LivingRoom_Window {channel="herzborg:curtain:livingroom:position"}
```

herzborg.sitemap:

```
TODO
```
