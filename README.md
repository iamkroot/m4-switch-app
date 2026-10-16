# M4 Switcher

Simple app for personal use.

## Motivation

The headphones only allow two devices to remain connected. And a third device cannot kick off an existing one to take its place.
The official (Flutter+obfuscated Dart) SmartControl app lets you manage these connections (as long as your phone is connected to the headphones.)
Might as well vibecode something that allows more programmatic switching.

## Features
- Only manages the connected devices list
- Supports `ACTION_SWITCH` intent so other apps can automate the switching

## Acknowledgements
- Protocol reference: https://github.com/xndbogdan/sennheiser-control/blob/main/PROTOCOL.md
- Gemini 3.8 Flash did the coding
