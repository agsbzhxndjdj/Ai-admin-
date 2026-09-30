import 'package:flutter/services.dart';

class AccessibilityBridge {
  static const _channel = MethodChannel('refine_loop/accessibility');

  Future<bool> isServiceEnabled() async {
    try {
      return await _channel.invokeMethod<bool>('isServiceEnabled') ?? false;
    } catch (_) {
      return false;
    }
  }

  Future<void> openAccessibilitySettings() =>
      _channel.invokeMethod('openAccessibilitySettings');

  Future<void> startLoop({
    required String task,
    required String reviewPrompt,
    required int rounds,
  }) =>
      _channel.invokeMethod('startLoop', {
        'task': task,
        'reviewPrompt': reviewPrompt,
        'rounds': rounds,
      });

  Future<void> stopLoop() => _channel.invokeMethod('stopLoop');

  Future<String> getStatus() async =>
      await _channel.invokeMethod<String>('getStatus') ?? 'غير معروف';
}
