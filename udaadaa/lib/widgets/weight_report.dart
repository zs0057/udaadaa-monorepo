import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:udaadaa/cubit/profile_cubit.dart';
import 'package:udaadaa/utils/constant.dart';
import 'package:udaadaa/widgets/legend_widget.dart';

class WeightReport extends StatelessWidget {
  const WeightReport({super.key});

  double sanitizeToY(double? value) {
    return (value == null || value.isNaN || value.isInfinite) ? 0 : value;
  }

  String getDate(DateTime date) {
    return "${date.month}/${date.day}";
  }

  BarChartGroupData chartData(
    int x,
    double val1,
  ) {
    return BarChartGroupData(
      x: x,
      groupVertically: true,
      barRods: [
        BarChartRodData(
          fromY: sanitizeToY(0.0),
          toY: sanitizeToY(val1),
          color: AppColors.primary,
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

    // ✅ 0kg 제외한 유효한 weight 값만 추림
    final validWeights =
        weeklyReport.map((e) => e?.weight ?? 0).where((w) => w != 0).toList();

    final double maxWeight = validWeights.isNotEmpty
        ? validWeights.reduce((a, b) => a > b ? a : b) + 0.5
        : 1.0;
    final double minWeight = validWeights.isNotEmpty
        ? validWeights.reduce((a, b) => a < b ? a : b) - 0.5
        : 0.0;

    final double range = maxWeight - minWeight;
    final double interval = range / 5;

    return Column(
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text("체중 변화", style: AppTextStyles.textTheme.displaySmall),
          ],
        ),
        AppSpacing.verticalSizedBoxL,
        SizedBox(
          height: 200,
          child: Padding(
            padding: const EdgeInsets.only(
                right: AppSpacing.xxl, left: AppSpacing.s),
            child: LineChart(
              LineChartData(
                minX: 0,
                maxX: 13,
                minY: minWeight,
                maxY: maxWeight,
                lineBarsData: [
                  LineChartBarData(
                    spots: List.generate(14, (index) {
                      final isToday =
                          selectedDate.year == DateTime.now().year &&
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

                      final report = weeklyReport[index];
                      final weight = sanitizeToY(report?.weight ?? 0.0);
                      // ✅ weight가 0이면 그래프에 안 보이게 null 처리
                      if (weight == 0.0) return null;
                      return FlSpot(index.toDouble(), weight);
                    }).whereType<FlSpot>().toList(),
                    isCurved: true,
                    preventCurveOverShooting: true,
                    color: AppColors.primary,
                    barWidth: 3,
                    dotData: FlDotData(show: true),
                    belowBarData: BarAreaData(
                      show: true,
                      color: AppColors.primary.withOpacity(0.4),
                    ),
                  ),
                ],
                titlesData: FlTitlesData(
                  bottomTitles: AxisTitles(
                    sideTitles: SideTitles(
                      showTitles: true,
                      interval: 1,
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
                  leftTitles: AxisTitles(
                    sideTitles: SideTitles(
                      showTitles: true,
                      reservedSize: 42,
                      interval: interval,
                      getTitlesWidget: (value, meta) {
                        // 양끝(min, max)은 제외
                        if (value == minWeight || value == maxWeight) {
                          return const SizedBox.shrink();
                        }

                        return Padding(
                          padding: const EdgeInsets.only(right: AppSpacing.m),
                          child: Text(
                            value.toStringAsFixed(1),
                            style: AppTextStyles.textTheme.bodySmall,
                            textAlign: TextAlign.end,
                          ),
                        );
                      },
                    ),
                  ),
                  topTitles: const AxisTitles(
                    sideTitles: SideTitles(showTitles: false),
                  ),
                  rightTitles: const AxisTitles(
                    sideTitles: SideTitles(showTitles: false),
                  ),
                ),
                gridData: const FlGridData(show: true),
                borderData: FlBorderData(show: false),
                lineTouchData: LineTouchData(
                  touchTooltipData: LineTouchTooltipData(
                    fitInsideHorizontally: true, // ✅ 양옆 짤림 방지
                    fitInsideVertically: true, // ✅ 위아래 짤림 방지
                    getTooltipColor: (group) => AppColors.primary[100]!,
                    tooltipRoundedRadius: 8,
                    getTooltipItems: (touchedSpots) {
                      return touchedSpots.map((spot) {
                        return LineTooltipItem(
                          '${spot.y.toStringAsFixed(1)} kg',
                          AppTextStyles.textTheme.bodySmall!,
                        );
                      }).toList();
                    },
                  ),
                ),
              ),
            ),
          ),
        ),
        AppSpacing.verticalSizedBoxS,
        LegendsListWidget(legends: [
          Legend("체중", AppColors.primary),
        ]),
      ],
    );
  }
}
