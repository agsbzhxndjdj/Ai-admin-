import 'dart:async';
import 'package:flutter/material.dart';
import 'accessibility_bridge.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _bridge = AccessibilityBridge();
  final _taskCtrl = TextEditingController(text: 'أنشئ لي تطبيق مراسلة');
  final _promptCtrl = TextEditingController(
      text:
          'اعطني رأيك بهذا واعطني العيوب والمشاكل والحلول والأشياء التي تحتاج تطوير');
  final _roundsCtrl = TextEditingController(text: '3');

  bool _serviceOn = false;
  String _status = 'جاهز';
  bool _running = false;
  Timer? _pollTimer;

  List<AppInfo> _apps = [];
  AppInfo? _executor;
  AppInfo? _reviewer;

  @override
  void initState() {
    super.initState();
    _refreshServiceState();
    _loadApps();
    _pollTimer =
        Timer.periodic(const Duration(seconds: 2), (_) => _refreshStatus());
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    super.dispose();
  }

  Future<void> _refreshServiceState() async {
    final on = await _bridge.isServiceEnabled();
    if (mounted) setState(() => _serviceOn = on);
  }

  Future<void> _loadApps() async {
    final apps = await _bridge.listInstalledApps();
    if (!mounted) return;
    setState(() {
      _apps = apps;
      // محاولة اختيار ذكية تلقائياً
      _executor ??= _guess(['qwen', 'tongyi', 'chatgpt', 'claude']);
      _reviewer ??= _guess(['gemini', 'bard']);
    });
  }

  AppInfo? _guess(List<String> keys) {
    for (final k in keys) {
      for (final a in _apps) {
        if (a.packageName.toLowerCase().contains(k) ||
            a.label.toLowerCase().contains(k)) return a;
      }
    }
    return null;
  }

  Future<void> _refreshStatus() async {
    if (!_running) return;
    final s = await _bridge.getStatus();
    if (!mounted) return;
    final finished =
        s.startsWith('🏁') || s.startsWith('✅') || s.startsWith('⛔');
    setState(() {
      _status = s;
      if (finished) _running = false;
    });
  }

  Future<void> _start() async {
    if (_executor == null || _reviewer == null) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('اختر تطبيق المنفّذ وتطبيق المراجع أولاً')));
      return;
    }
    final rounds = int.tryParse(_roundsCtrl.text) ?? 3;
    setState(() {
      _running = true;
      _status = 'جارٍ البدء...';
    });
    await _bridge.startLoop(
      task: _taskCtrl.text,
      reviewPrompt: _promptCtrl.text,
      rounds: rounds,
      executorPackage: _executor!.packageName,
      reviewerPackage: _reviewer!.packageName,
    );
  }

  Future<void> _stop() async {
    await _bridge.stopLoop();
    setState(() {
      _running = false;
      _status = 'تم الإيقاف';
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('حلقة التحسين الذكية')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            color: _serviceOn ? Colors.green.shade50 : Colors.orange.shade50,
            child: ListTile(
              leading: Icon(_serviceOn ? Icons.check_circle : Icons.warning,
                  color: _serviceOn ? Colors.green : Colors.orange),
              title: Text(
                  _serviceOn ? 'خدمة الوصول مفعّلة' : 'خدمة الوصول غير مفعّلة'),
              trailing: TextButton(
                onPressed: () async {
                  await _bridge.openAccessibilitySettings();
                  await Future.delayed(const Duration(seconds: 1));
                  _refreshServiceState();
                },
                child: const Text('فتح الإعدادات'),
              ),
            ),
          ),
          const SizedBox(height: 12),

          // ★ اختيار التطبيقات من داخل التطبيق ★
          DropdownButtonFormField<AppInfo>(
            value: _executor,
            isExpanded: true,
            decoration: const InputDecoration(
                labelText: 'تطبيق المنفّذ', border: OutlineInputBorder()),
            items: [
              for (final app in _apps)
                DropdownMenuItem(
                    value: app,
                    child: Text(app.label,
                        overflow: TextOverflow.ellipsis)),
            ],
            onChanged: (v) => setState(() => _executor = v),
          ),
          const SizedBox(height: 12),
          DropdownButtonFormField<AppInfo>(
            value: _reviewer,
            isExpanded: true,
            decoration: const InputDecoration(
                labelText: 'تطبيق المراجع', border: OutlineInputBorder()),
            items: [
              for (final app in _apps)
                DropdownMenuItem(
                    value: app,
                    child: Text(app.label,
                        overflow: TextOverflow.ellipsis)),
            ],
            onChanged: (v) => setState(() => _reviewer = v),
          ),
          const SizedBox(height: 12),

          TextField(
            controller: _taskCtrl,
            maxLines: 3,
            decoration: const InputDecoration(
                labelText: 'المهمة', border: OutlineInputBorder()),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _promptCtrl,
            maxLines: 3,
            decoration: const InputDecoration(
                labelText: 'أمر المراجعة المخصص',
                border: OutlineInputBorder()),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _roundsCtrl,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
                labelText: 'عدد الدورات (0 = غير محدود)',
                border: OutlineInputBorder()),
          ),
          const SizedBox(height: 20),
          Row(
            children: [
              Expanded(
                child: FilledButton.icon(
                  onPressed: _serviceOn && !_running ? _start : null,
                  icon: const Icon(Icons.play_arrow),
                  label: const Text('بدء الحلقة'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: _running ? _stop : null,
                  icon: const Icon(Icons.stop),
                  label: const Text('إيقاف'),
                ),
              ),
            ],
          ),
          const SizedBox(height: 24),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: Colors.black87,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('الحالة الحالية',
                    style: TextStyle(color: Colors.white54, fontSize: 12)),
                const SizedBox(height: 6),
                Text(_status,
                    style: const TextStyle(color: Colors.white, fontSize: 15)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
