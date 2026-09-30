import 'package:flutter/services.dart';

class AppInfo {
  final String packageName;
  final String label;
  const AppInfo({required this.packageName, required this.label});

  factory AppInfo.fromMap(Map<dynamic, dynamic> m) => AppInfo(
        packageName: m['package'] as String,
        label: m['label'] as String,
      );

  @override
  bool operator ==(Object other) =>
      other is AppInfo && other.packageName == packageName;

  @override
  int get hashCode => packageName.hashCode;
}

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

  Future<List<AppInfo>> listInstalledApps() async {
    try {
      final result = await _channel.invokeMethod<List<dynamic>>('listApps');
      final apps = (result ?? [])
          .map((e) => AppInfo.fromMap(e as Map<dynamic, dynamic>))
          .toList();
      apps.sort((a, b) => a.label.compareTo(b.label));
      return apps;
    } catch (_) {
      return [];
    }
  }

  Future<void> startCalibration(String packageName) =>
      _channel.invokeMethod('startCalibration', {'package': packageName});

  Future<bool> isCalibrated(String packageName) async =>
      await _channel.invokeMethod<bool>(
          'isCalibrated', {'package': packageName}) ??
      false;

  Future<void> startLoop({
    required String task,
    required String reviewPrompt,
    required int rounds,
    required String executorPackage,
    required String reviewerPackage,
  }) =>
      _channel.invokeMethod('startLoop', {
        'task': task,
        'reviewPrompt': reviewPrompt,
        'rounds': rounds,
        'executorPackage': executorPackage,
        'reviewerPackage': reviewerPackage,
      });

  Future<void> stopLoop() => _channel.invokeMethod('stopLoop');

  Future<String> getStatus() async =>
      await _channel.invokeMethod<String>('getStatus') ?? 'غير معروف';
}
