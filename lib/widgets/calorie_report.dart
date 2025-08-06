import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:udaadaa/cubit/profile_cubit.dart';
import 'package:udaadaa/utils/constant.dart';
import 'package:udaadaa/widgets/legend_widget.dart';

class CalorieReport extends StatelessWidget {
  const CalorieReport({super.key});

  double sanitizeToY(double? value) {
    return (value == null || value.isNaN || value.isInfinite) ? 0 : value;
  }

  String getDate(DateTime date) {
    return "${date.month}/${date.day}";
  }

  BarChartGroupData chartData(
    int x,
    double val1,
    double val2,
    double val3,
    double val4,
  ) {
    return BarChartGroupData(
      x: x,
      groupVertically: true,
      barRods: [
        BarChartRodData(
          fromY: sanitizeToY(0.0),
          toY: sanitizeToY(val1),
          color: const Color(0xFFFF6384), // 핑크색 (아침)
        ),
        BarChartRodData(
          fromY: sanitizeToY(val1),
          toY: (val1 + val2),
          color: const Color(0xFF4BC0C0), // 틸색 (점심)
        ),
        BarChartRodData(
          fromY: sanitizeToY(val1 + val2),
          toY: (val1 + val2 + val3),
          color: const Color(0xFF36A2EB), // 파란색 (저녁)
        ),
        BarChartRodData(
          fromY: sanitizeToY(val1 + val2 + val3),
          toY: (val1 + val2 + val3 + val4),
          color: const Color(0xFF9966FF), // 보라색 (간식)
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final weeklyReport = context.watch<ProfileCubit>().getWeeklyReport;
    final selectedDate = context.select<ProfileCubit, DateTime?>(
            (cubit) => cubit.getSelectedDate) ??
        DateTime.now();
    return Column(
      children: [
        Text("칼로리 리포트", style: AppTextStyles.textTheme.displaySmall),
        AppSpacing.verticalSizedBoxL,
        SizedBox(
          height: 200, // 차트 높이 설정
          child: BarChart(
            BarChartData(
              barGroups: List.generate(14, (index) {
                final isToday = selectedDate.year == DateTime.now().year &&
                    selectedDate.month == DateTime.now().month &&
                    selectedDate.day == DateTime.now().day;
                DateTime targetDate;

                if (isToday) {
                  // 오늘 날짜인 경우: 14일 전부터 오늘까지
                  targetDate =
                      selectedDate.subtract(Duration(days: 13 - index));
                } else {
                  // 다른 날짜인 경우: 선택된 날짜부터 13일 후까지
                  targetDate = selectedDate.add(Duration(days: index));
                }

                // 해당 날짜의 리포트 데이터 가져오기
                final report = weeklyReport[index];

                return chartData(
                  index,
                  report?.breakfast?.toDouble() ?? 0.0,
                  report?.lunch?.toDouble() ?? 0.0,
                  report?.dinner?.toDouble() ?? 0.0,
                  report?.snack?.toDouble() ?? 0.0,
                );
              }),
              barTouchData: BarTouchData(
                enabled: true,
                touchTooltipData: BarTouchTooltipData(
                  fitInsideHorizontally: true, // ✅ 양옆 짤림 방지
                  fitInsideVertically: true, // ✅ 위아래 짤림 방지
                  getTooltipColor: (group) => AppColors.primary[100]!,
                  getTooltipItem: (group, groupIndex, rod, rodIndex) {
                    final allValue = group.barRods[3].toY.toInt();
                    final value = (rod.toY - rod.fromY).toInt();
                    final label = [
                      '아침',
                      '점심',
                      '저녁',
                      '간식',
                    ];

                    final text =
                        '${label[rodIndex]} $value kcal\n총합 $allValue kcal';

                    return BarTooltipItem(
                      text,
                      AppTextStyles.textTheme.bodySmall!,
                    );
                  },
                ),
              ),
              titlesData: FlTitlesData(
                bottomTitles: AxisTitles(
                  sideTitles: SideTitles(
                    showTitles: true,
                    getTitlesWidget: (double value, TitleMeta meta) {
                      final style = AppTextStyles.textTheme.bodySmall;
                      final index = value.toInt();
                      final isToday =
                          selectedDate.year == DateTime.now().year &&
                              selectedDate.month == DateTime.now().month &&
                              selectedDate.day == DateTime.now().day;

                      // 짝수 인덱스(0, 2, 4, 6, 8, 10, 12)만 날짜 표시
                      if (index % 2 != 0) {
                        return Text('', style: style);
                      }

                      if (isToday) {
                        // 오늘 날짜인 경우: 14일 전부터 오늘까지
                        switch (index) {
                          case 0:
                            return Text(
                                getDate(
                                  selectedDate
                                      .subtract(const Duration(days: 13)),
                                ),
                                style: style);
                          case 2:
                            return Text(
                                getDate(
                                  selectedDate
                                      .subtract(const Duration(days: 11)),
                                ),
                                style: style);
                          case 4:
                            return Text(
                                getDate(
                                  selectedDate
                                      .subtract(const Duration(days: 9)),
                                ),
                                style: style);
                          case 6:
                            return Text(
                                getDate(
                                  selectedDate
                                      .subtract(const Duration(days: 7)),
                                ),
                                style: style);
                          case 8:
                            return Text(
                                getDate(
                                  selectedDate
                                      .subtract(const Duration(days: 5)),
                                ),
                                style: style);
                          case 10:
                            return Text(
                                getDate(
                                  selectedDate
                                      .subtract(const Duration(days: 3)),
                                ),
                                style: style);
                          case 12:
                            return Text(
                                getDate(
                                  selectedDate
                                      .subtract(const Duration(days: 1)),
                                ),
                                style: style);
                          default:
                            return Text('', style: style);
                        }
                      } else {
                        // 다른 날짜인 경우: 선택된 날짜가 맨 왼쪽에 오도록
                        switch (index) {
                          case 0:
                            return Text(getDate(selectedDate), style: style);
                          case 2:
                            return Text(
                                getDate(
                                  selectedDate.add(const Duration(days: 2)),
                                ),
                                style: style);
                          case 4:
                            return Text(
                                getDate(
                                  selectedDate.add(const Duration(days: 4)),
                                ),
                                style: style);
                          case 6:
                            return Text(
                                getDate(
                                  selectedDate.add(const Duration(days: 6)),
                                ),
                                style: style);
                          case 8:
                            return Text(
                                getDate(
                                  selectedDate.add(const Duration(days: 8)),
                                ),
                                style: style);
                          case 10:
                            return Text(
                                getDate(
                                  selectedDate.add(const Duration(days: 10)),
                                ),
                                style: style);
                          case 12:
                            return Text(
                                getDate(
                                  selectedDate.add(const Duration(days: 12)),
                                ),
                                style: style);
                          default:
                            return Text('', style: style);
                        }
                      }
                    },
                  ),
                ),
                rightTitles: const AxisTitles(
                  sideTitles: SideTitles(showTitles: false),
                ),
                topTitles: const AxisTitles(
                  sideTitles: SideTitles(showTitles: false),
                ),
                leftTitles: AxisTitles(
                  sideTitles: SideTitles(
                    showTitles: true,
                    reservedSize: 32,
                    maxIncluded: false,
                    getTitlesWidget: (value, meta) => Text(
                      value.toInt().toString(),
                      style: AppTextStyles.textTheme.bodySmall,
                      textAlign: TextAlign.end,
                    ),
                  ),
                ),
              ),
              gridData: const FlGridData(show: true),
              borderData: FlBorderData(show: false),
            ),
          ),
        ),
        AppSpacing.verticalSizedBoxS,
        LegendsListWidget(legends: [
          Legend("아침", const Color(0xFFFF6384)), // 핑크색
          Legend("점심", const Color(0xFF4BC0C0)), // 틸색
          Legend("저녁", const Color(0xFF36A2EB)), // 파란색
          Legend("간식", const Color(0xFF9966FF)), // 보라색
        ]),
      ],
    );
  }
}
