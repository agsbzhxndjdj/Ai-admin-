import 'package:flutter/material.dart';
import 'src/home_screen.dart';

void main() => runApp(const RefineApp());

class RefineApp extends StatelessWidget {
  const RefineApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'حلقة التحسين',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorSchemeSeed: Colors.deepPurple,
        useMaterial3: true,
      ),
      home: const HomeScreen(),
    );
  }
}
