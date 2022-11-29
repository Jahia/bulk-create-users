angular.module('siteSetting', [])
    .controller('languages', ['$scope', '$http', '$timeout', 'languagesConstants',
        function ($scope, $http, $timeout, languagesConstants) {

            // initialize scope data
            $scope.newLanguages = [];
            $scope.displayLoading = false;

            $scope.site = {
                currentLocale: languagesConstants.currentLocale,
                siteDefaultLanguage: languagesConstants.siteDefaultLanguage,
                siteLocales: languagesConstants.siteLocales,
                availableLocales: languagesConstants.availableLocales,
                mixLanguages: languagesConstants.mixLanguages,
                allowsUnlistedLanguages: languagesConstants.allowsUnlistedLanguages
            };

            for (var i = 0; i < $scope.site.siteLocales.length; i++) {
                var siteLocale = $scope.site.siteLocales[i];

                siteLocale.mandatory = languagesConstants.mandatoryLanguages.indexOf(siteLocale.locale) !== -1;
                siteLocale.activeEdit = languagesConstants.activeDefaultLanguages.indexOf(siteLocale.locale) !== -1;
                siteLocale.activeLive = languagesConstants.activeLiveLanguages.indexOf(siteLocale.locale) !== -1;
            }

            var getCount = function (siteLocale) {
                if (!siteLocale.calculatingCount) {
                    siteLocale.calculatingCount = true;
                    $http({
                        method  : 'GET',
                        url     : languagesConstants.siteUrl + ".languagesCount.do?locale=" + siteLocale.locale,
                        headers : { 'Content-Type': 'application/json' }
                    }).then(function successCallback(result) {
                        if (result && result.data && result.status === 200) {
                            siteLocale.count = result.data.count;
                        }
                        siteLocale.calculatingCount = false;
                    });
                }
            };

            /**
             * Watcher on defaultLanguage that set language to active for edit and live when selected as default
             */
            $scope.$watch("site.siteDefaultLanguage", function(newValue, oldValue, scope) {
                for (var i = 0; i < $scope.site.siteLocales.length; i++) {
                    var siteLocale = $scope.site.siteLocales[i];
                    if (siteLocale.locale === newValue) {
                        siteLocale.activeLive = true;
                        siteLocale.activeEdit = true;
                    }
                }
            });

            /**
             * Watcher on site locales:
             * - automatically set live not active when edit is not active
             * - automatically request count of contents for language if necessary
             */
            $scope.$watch("site.siteLocales", function(newValue, oldValue, scope) {
                for (var i = 0; i < $scope.site.siteLocales.length; i++) {
                    var siteLocale = $scope.site.siteLocales[i];

                    // automatically disable live if edit is deactivate
                    if (!siteLocale.activeEdit && siteLocale.activeLive) {
                        siteLocale.activeLive = false;
                    }

                    // get count if necessary
                    if (!siteLocale.activeEdit && siteLocale.count === undefined) {
                        getCount(siteLocale);
                    }
                }
            }, true);

            /**
             * Filter out site locales from available locales
             * @param availableLocale
             * @returns {boolean}
             */
            $scope.filterSiteLocales = function(availableLocale) {
                 for (var i = 0; i < $scope.site.siteLocales.length; i++) {
                    var siteLocale = $scope.site.siteLocales[i];
                    if(siteLocale.locale === availableLocale.locale) {
                        return false;
                    }
                }

                return true;
            };

            /**
             * Add new languages to the table
             */
            $scope.addLanguage = function() {
                for (var i = 0; i < $scope.newLanguages.length; i++) {
                    var newLanguage = $scope.newLanguages[i];

                    // add the new language
                    $scope.site.siteLocales.push({
                        "locale": newLanguage.locale,
                        "displayLocale": newLanguage.displayLocale,
                        "mandatory": false,
                        "activeEdit": false,
                        "activeLive": false,
                        "count": 0
                    });
                }

                // reset selection
                $scope.newLanguages = [];

                if ($.material) {
                    $timeout(function() {
                        $.material.init();
                    }, 0);
                }
            };

            /**
             * Edit language is disabled when:
             * - language is the default language
             * - language is the current ui locale
             * @param siteLocale
             * @returns {boolean}
             */
            $scope.isEditDisabled = function(siteLocale) {
                return (siteLocale.locale === $scope.site.siteDefaultLanguage) || (siteLocale.locale === $scope.site.currentLocale);
            };

            /**
             * Live language is disabled when:
             * - language is the default language
             * - language is not active in Edit
             * @param siteLocale
             */
            $scope.isLiveDisabled = function(siteLocale) {
                return (siteLocale.locale === $scope.site.siteDefaultLanguage) || !siteLocale.activeEdit;
            };

            /**
             * check if locale can be deleted, languages that can't be deleted:
             * - default language
             * - current locale
             * - languages with count > 0
             */
            $scope.canBeDeleted = function(siteLocale) {
                return !siteLocale.activeEdit && siteLocale.locale !== $scope.site.siteDefaultLanguage && siteLocale.locale !== $scope.site.currentLocale && siteLocale.count !== undefined && siteLocale.count === 0;
            };

            /**
             * get the reason why the language can't be deleted
             */
            $scope.getNotDeletableReason = function(siteLocale) {
                if (siteLocale.locale === $scope.site.currentLocale) {
                    return "current";
                }
                if (siteLocale.locale === $scope.site.siteDefaultLanguage) {
                    return "default";
                }
                if (siteLocale.activeEdit) {
                    return "active";
                }
                if (siteLocale.count !== undefined && siteLocale.count > 0) {
                    return "contents"
                }
            };

            /**
             * delete the language from the table
             */
            $scope.delete = function($index) {
                $scope.site.siteLocales.splice($index, 1);
            };

            /**
             * Save the languages on the site node
             */
            $scope.save = function() {
                $scope.displayLoading = true;

                var languages = [];
                var mandatoryLanguages = [];
                var inactiveLanguages = [];
                var inactiveLiveLanguages = [];

                for (var i = 0; i < $scope.site.siteLocales.length; i++) {
                    var siteLocale = $scope.site.siteLocales[i];

                    if(!siteLocale.activeEdit) {
                        inactiveLanguages.push(siteLocale.locale);
                    } else {
                        languages.push(siteLocale.locale);
                    }
                    if(!siteLocale.activeLive) {
                        inactiveLiveLanguages.push(siteLocale.locale);
                    }
                    if(siteLocale.mandatory) {
                        mandatoryLanguages.push(siteLocale.locale);
                    }
                }

                $http({
                    method  : 'PUT',
                    url     : languagesConstants.siteRestUrl,
                    data    : {"properties" : {
                        "j__defaultLanguage": {"value": $scope.site.siteDefaultLanguage},
                        "j__languages": {"multiValued": true, "value": languages},
                        "j__mandatoryLanguages": {"multiValued": true, "value": mandatoryLanguages},
                        "j__inactiveLanguages": {"multiValued": true, "value": inactiveLanguages},
                        "j__inactiveLiveLanguages": {"multiValued": true, "value": inactiveLiveLanguages},
                        "j__mixLanguage": {"value": $scope.site.mixLanguages},
                        "j__allowsUnlistedLanguages": {"value": $scope.site.allowsUnlistedLanguages}
                        }},
                    headers : { 'Content-Type': 'application/json' }
                }).then(function successCallback() {
                    top.location.reload();
                }, function errorCallback() {
                    $scope.displayLoading = false;
                });
            }
        }]);