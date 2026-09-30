class LoopConfig {
  final String task;
  final String reviewPrompt;
  final int rounds; // 0 = غير محدود
  const LoopConfig({
    required this.task,
    required this.reviewPrompt,
    required this.rounds,
  });
}
